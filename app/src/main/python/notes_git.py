"""Student-owned private copies of public notes. Credentials never enter Git config."""
import io
import json
import os
import re
import threading
from pathlib import Path
from urllib.parse import urlparse

import requests
from dulwich import porcelain
from dulwich.graph import find_merge_base
from dulwich.index import commit_tree
from dulwich.object_store import iter_tree_contents
from dulwich.repo import Repo

_LOCK = threading.RLock()
_API = "https://api.github.com"
_MARKER = "mit-notes.json"
_PENDING = "mit-notes-conflicts.json"


class NotesError(Exception):
    pass


def _api(token, method, path, **kwargs):
    response = requests.request(method, _API + path, headers={
        "Authorization": "Bearer " + token,
        "Accept": "application/vnd.github+json",
    }, timeout=(15, 60), **kwargs)
    if response.status_code == 401:
        raise NotesError("GitHub token expired or invalid. Reconnect your account.")
    if response.status_code == 403:
        raise NotesError("GitHub denied access. Check token permissions, organization policy, or rate limits.")
    return response


def _json(response, codes=(200,)):
    if response.status_code not in codes:
        raise NotesError("GitHub request failed (HTTP %s)." % response.status_code)
    return response.json()


def _identity(token):
    if not token or any(c.isspace() for c in token):
        raise NotesError("Enter a valid GitHub personal access token.")
    response = _api(token, "GET", "/user")
    user = _json(response)
    scopes = response.headers.get("X-OAuth-Scopes")
    if scopes is not None and "repo" not in {s.strip() for s in scopes.split(",")}:
        raise NotesError("This classic token needs the repo scope to create private repositories.")
    return user["login"]


def parse_source(url):
    parsed = urlparse(url.strip())
    if parsed.scheme != "https" or parsed.netloc.lower() != "github.com" or parsed.query or parsed.fragment:
        raise NotesError("Use a repository URL such as https://github.com/class/subject-notes")
    parts = parsed.path.strip("/").split("/")
    if len(parts) != 2:
        raise NotesError("Paste the repository link, not a file or branch link.")
    owner, name = parts[0], parts[1].removesuffix(".git")
    if not re.fullmatch(r"[A-Za-z0-9-]+", owner) or not re.fullmatch(r"[A-Za-z0-9_.-]+", name) or name in (".", ".."):
        raise NotesError("Invalid GitHub repository link.")
    return owner + "/" + name


def _source(token, url):
    repo = _json(_api(token, "GET", "/repos/" + parse_source(url)))
    if repo["private"] or repo.get("visibility", "public") != "public":
        raise NotesError("Choose the public class notes repository.")
    return repo


def _result(function, *args):
    with _LOCK:
        try:
            return json.dumps(dict(ok=True, **function(*args)))
        except NotesError as error:
            return json.dumps(dict(ok=False, message=str(error)))
        except requests.RequestException:
            return json.dumps(dict(ok=False, message="Cannot reach GitHub. Check your connection and retry."))
        except Exception as error:
            # Do not surface transport exception strings: they can contain authentication details.
            return json.dumps(dict(ok=False, message="Git operation failed (%s). Your saved files and history are retained." % type(error).__name__))


def validate_account(token):
    return _result(lambda: dict(username=_identity(token)))


def inspect_source(token, url):
    def inspect():
        source = _source(token, url)
        name = "mit-notes-" + source["full_name"].replace("/", "-")
        if len(name) > 90:
            name = name[:78] + "-" + str(source["id"])
        return dict(source=source["full_name"], source_id=str(source["id"]),
                    private_repo=name, branch=source["default_branch"])
    return _result(inspect)


def _write_json(path, data):
    temporary = str(path) + ".tmp"
    with open(temporary, "w", encoding="utf-8") as stream:
        json.dump(data, stream)
        stream.flush()
        os.fsync(stream.fileno())
    os.replace(temporary, path)


def _auth(token):
    return dict(username="x-access-token", password=token, errstream=io.BytesIO())


def _configure(repo, origin, upstream=None):
    config = repo.get_config()
    config.set((b"remote", b"origin"), b"url", origin.encode())
    config.set((b"remote", b"origin"), b"fetch", b"+refs/heads/*:refs/remotes/origin/*")
    if upstream:
        config.set((b"remote", b"upstream"), b"url", upstream.encode())
        config.set((b"remote", b"upstream"), b"fetch", b"+refs/heads/*:refs/remotes/upstream/*")
    config.write_to_path()


def connect(root, token, username, url):
    return _result(_connect, root, token, username, url)


def _connect(root, token, username, url):
    if _identity(token).lower() != username.lower():
        raise NotesError("Token account changed. Reconnect your GitHub account first.")
    source = _source(token, url)
    name = "mit-notes-" + source["full_name"].replace("/", "-")
    if len(name) > 90:
        name = name[:78] + "-" + str(source["id"])
    description = "MIT Attendance private notes; upstream-id=" + str(source["id"])
    remote = _api(token, "GET", "/repos/" + username + "/" + name)
    if remote.status_code == 404:
        remote = _api(token, "POST", "/user/repos", json={
            "name": name, "private": True, "description": description,
            "auto_init": False, "has_issues": False, "has_wiki": False,
        })
        if remote.status_code == 422:  # Setup retry or a concurrent device.
            remote = _api(token, "GET", "/repos/" + username + "/" + name)
    private = _json(remote, (200, 201))
    if (not private["private"] or private.get("fork") or
            private["owner"]["login"].lower() != username.lower() or
            private.get("description") != description):
        raise NotesError("A different or public repository already uses this name. Rename it on GitHub before connecting.")
    folder = Path(root) / username.lower() / str(source["id"])
    folder.parent.mkdir(parents=True, exist_ok=True)
    marker = folder / ".git" / _MARKER
    metadata = dict(username=username, source_id=str(source["id"]), source=source["full_name"],
                    upstream=source["clone_url"], origin=private["clone_url"],
                    private_repo=private["full_name"], branch=source["default_branch"])
    if folder.exists():
        if not marker.is_file():
            raise NotesError("An incomplete local copy exists. Your files were kept; rename that folder and reconnect.")
        saved = json.loads(marker.read_text())
        if saved["source_id"] != metadata["source_id"] or saved["username"].lower() != username.lower():
            raise NotesError("Local copy belongs to another account or subject.")
        metadata = saved
    else:
        # A staging folder is never adopted as a finished copy after an interrupted clone.
        import tempfile
        staging = Path(tempfile.mkdtemp(prefix=".connecting-", dir=folder.parent))
        branches = _json(_api(token, "GET", "/repos/" + private["full_name"] + "/branches"))
        has_private_history = bool(branches)
        clone_url = private["clone_url"] if has_private_history else source["clone_url"]
        branch = private["default_branch"] if has_private_history else source["default_branch"]
        metadata["branch"] = branch
        with porcelain.clone(clone_url, str(staging), branch=branch, checkout=False, **_auth(token)) as repo:
            _tree(repo, repo[repo.head()].tree)  # Reject unsafe or unsupported trees before adoption.
            porcelain.reset(repo, "hard", repo.head())
            _configure(repo, private["clone_url"], source["clone_url"])
            _write_json(staging / ".git" / _MARKER, metadata)
        os.rename(staging, folder)
    # Initial publication is idempotent, and never overwrites a changed remote branch.
    result = _sync(str(folder), token, username, True)
    result.update(folder=str(folder), metadata=metadata)
    return result


def _tree(repo, tree_id):
    entries = {}
    for entry in iter_tree_contents(repo.object_store, tree_id):
        path = entry.path.decode("utf-8")
        if (entry.mode not in (0o100644, 0o100755) or
                any(p.lower() == ".git" or p in ("", ".", "..") for p in path.split("/")) or
                "\\" in path or "\0" in path):
            raise NotesError("Notes repositories must contain regular files only, without submodules or symbolic links.")
        entries[path] = (entry.sha, entry.mode)
    return entries


def _author(username):
    return (username + " <" + username + "@users.noreply.github.com>").encode()


def _snapshot(repo, username):
    # Include additions, modifications, and deletions, respecting .gitignore.
    for directory, directories, files in os.walk(repo.path, followlinks=False):
        directories[:] = [d for d in directories if d != ".git"]
        if any(os.path.islink(os.path.join(directory, f)) for f in directories + files):
            raise NotesError("Remove symbolic links from the notes folder before syncing.")
    porcelain.add(repo)
    status = porcelain.status(repo)
    if any(status.staged.values()):
        porcelain.commit(repo, message=b"Save personal notes", author=_author(username), committer=_author(username))
    return repo.head()


def merge_entries(base, mine, incoming, choices=None):
    """Conservative three-way file merge. Binary and text conflicts use the same UI."""
    choices = choices or {}
    merged, conflicts = {}, []
    for path in sorted(set(base) | set(mine) | set(incoming)):
        original, local, remote = base.get(path), mine.get(path), incoming.get(path)
        if local == remote or remote == original:
            selected = local
        elif local == original:
            selected = remote
        elif choices.get(path) == "mine":
            selected = local
        elif choices.get(path) == "class":
            selected = remote
        elif choices.get(path) == "both" and local is not None and remote is not None:
            selected = local
            stem, extension = os.path.splitext(path)
            other = stem + " (class)" + extension
            counter = 2
            while other in base or other in mine or other in incoming or other in merged:
                other = stem + " (class %s)" % counter + extension
                counter += 1
            merged[other] = remote
        else:
            conflicts.append(path)
            continue
        if selected is not None:
            merged[path] = selected
    paths = set(merged)
    for path in paths:
        if any("/".join(path.split("/")[:i]) in paths for i in range(1, len(path.split("/")))):
            raise NotesError("A file and folder have conflicting names. Rename your local file or folder and retry.")
    return merged, conflicts


def _merge(repo, incoming, username, choices=None):
    mine = repo.head()
    if mine == incoming:
        return []
    bases = find_merge_base(repo, [mine, incoming])
    if len(bases) != 1:
        raise NotesError("Repositories have unrelated or ambiguous history. Reconnect the correct private copy.")
    if bases[0] == incoming:
        return []
    old = _tree(repo, repo[mine].tree)
    merged, conflicts = merge_entries(_tree(repo, repo[bases[0]].tree), old,
                                     _tree(repo, repo[incoming].tree), choices)
    if conflicts:
        _write_json(Path(repo.controldir()) / _PENDING, dict(head=mine.decode(), incoming=incoming.decode(), paths=conflicts))
        return conflicts
    # Check for ignored/untracked files that a checkout would otherwise overwrite.
    for path in merged:
        target = Path(repo.path) / path
        if path not in old and target.exists() and (not target.is_file() or target.read_bytes() != repo[merged[path][0]].data):
            raise NotesError("An untracked file would be overwritten: " + path + ". Move it aside and retry.")
        for parent in target.parents:
            if parent == Path(repo.path):
                break
            if parent.is_symlink():
                raise NotesError("A symbolic link blocks the update. Remove it and retry.")
    tree = commit_tree(repo.object_store, [(p.encode(), sha, mode) for p, (sha, mode) in merged.items()])
    # All local work is already committed; the merge retains both parents for recovery.
    commit = repo.do_commit(message=b"Merge notes updates", tree=tree,
                            author=_author(username), committer=_author(username), merge_heads=[incoming])
    porcelain.reset(repo, "hard", commit)
    pending = Path(repo.controldir()) / _PENDING
    if pending.exists():
        pending.unlink()
    return []


def _open(folder, token, username):
    if _identity(token).lower() != username.lower():
        raise NotesError("Token account changed. Reconnect your GitHub account.")
    repo = Repo(folder)
    metadata = json.loads((Path(repo.controldir()) / _MARKER).read_text())
    if metadata["username"].lower() != username.lower():
        repo.close()
        raise NotesError("This notes copy belongs to another GitHub account.")
    private = _json(_api(token, "GET", "/repos/" + metadata["private_repo"]))
    if (not private["private"] or private.get("fork") or
            private["owner"]["login"].lower() != username.lower()):
        repo.close()
        raise NotesError("Your notes repository is no longer an independent private repository. Sync stopped.")
    source = _json(_api(token, "GET", "/repos/" + metadata["source"]))
    if str(source["id"]) != metadata["source_id"]:
        repo.close()
        raise NotesError("The class repository identity changed. Reconnect the correct subject.")
    # Derive authenticated destinations from GitHub, never from editable local Git config.
    _configure(repo, private["clone_url"], source["clone_url"])
    return repo, metadata


def sync(folder, token, username, push=False):
    return _result(_sync, folder, token, username, push)


def _sync(folder, token, username, push=False):
    repo, metadata = _open(folder, token, username)
    with repo:
        pending = Path(repo.controldir()) / _PENDING
        if pending.exists():
            state = json.loads(pending.read_text())
            return dict(conflicts=state["paths"], message="Resolve the pending notes conflicts first.")
        _snapshot(repo, username)
        # Pull the student's other-device changes before class updates or pushing.
        for remote in ("origin", "upstream"):
            fetched = porcelain.fetch(repo, remote, **_auth(token))
            if remote == "upstream":
                source = _json(_api(token, "GET", "/repos/" + metadata["source"]))
                branch = source["default_branch"]
            else:
                branch = metadata["branch"]
            incoming = fetched.refs.get(("refs/heads/" + branch).encode())
            if incoming:
                _snapshot(repo, username)
                conflicts = _merge(repo, incoming, username)
                if conflicts:
                    return dict(conflicts=conflicts, message="Choose how to keep the conflicting files.")
            elif remote == "upstream":
                raise NotesError("The class repository has no default branch to sync.")
        if push:
            private = _json(_api(token, "GET", "/repos/" + metadata["private_repo"]))
            if not private["private"] or private.get("fork") or private["owner"]["login"].lower() != username.lower():
                raise NotesError("Private repository access changed. Upload stopped.")
            result = porcelain.push(repo, "origin", [b"HEAD:refs/heads/" + metadata["branch"].encode()],
                                    outstream=io.BytesIO(), **_auth(token))
            if any(result.ref_status.values()):
                raise NotesError("Push rejected because the remote changed. Pull updates and retry.")
        return dict(message="Notes pushed to your private repository." if push else "Class and personal notes are up to date.", conflicts=[])


def resolve(folder, token, username, choices_json):
    def resolve_pending():
        repo, metadata = _open(folder, token, username)
        with repo:
            pending = Path(repo.controldir()) / _PENDING
            state = json.loads(pending.read_text())
            if _snapshot(repo, username).decode() != state["head"]:
                pending.unlink()
                raise NotesError("Local notes changed while resolving conflicts. Pull again to compare the latest files.")
            conflicts = _merge(repo, state["incoming"].encode(), username, json.loads(choices_json))
            return dict(message="Conflicts resolved. Pull again to finish syncing, or push to upload.", conflicts=conflicts)
    return _result(resolve_pending)
