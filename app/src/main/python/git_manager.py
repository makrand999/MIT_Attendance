"""Private, account-owned backup repositories. Notes use notes_git instead."""
import io
from pathlib import Path
from dulwich import porcelain
from dulwich.repo import Repo
import notes_git as notes


def _open(root_path, repo_name, token):
    if repo_name != "mit-attendance-backup":
        raise notes.NotesError("Use subject notes setup to connect a notes repository.")
    username = notes._identity(token)
    remote = notes._api(token, "GET", "/repos/" + username + "/" + repo_name)
    if remote.status_code == 404:
        remote = notes._api(token, "POST", "/user/repos", json={
            "name": repo_name, "private": True,
            "description": "MIT Attendance personal account backup", "auto_init": False,
        })
    metadata = notes._json(remote, (200, 201))
    if not metadata["private"] or metadata.get("fork") or metadata["owner"]["login"].lower() != username.lower():
        raise notes.NotesError("Backup must be an independent private repository owned by your account.")
    directory = Path(root_path) / repo_name
    if not (directory / ".git").exists():
        directory.mkdir(parents=True, exist_ok=True)
        repo = Repo.init(str(directory))
    else:
        repo = Repo(str(directory))
    notes._configure(repo, metadata["clone_url"])
    return repo, username, metadata.get("default_branch", "main")


def _pull(repo, username, branch, token):
    fetched = porcelain.fetch(repo, "origin", **notes._auth(token))
    incoming = fetched.refs.get(("refs/heads/" + branch).encode())
    if incoming is None:
        return
    try:
        repo.head()
    except KeyError:
        # Never overwrite a local session while initializing a restored backup.
        files = [p for p in Path(repo.path).rglob("*") if ".git" not in p.parts and p.is_file()]
        if files:
            raise notes.NotesError("Local backup files exist without history. Keep them aside before restoring.")
        notes._tree(repo, repo[incoming].tree)
        repo.refs.set_symbolic_ref(b"HEAD", ("refs/heads/" + branch).encode())
        repo[("refs/heads/" + branch).encode()] = incoming
        porcelain.reset(repo, "hard", incoming)
        return
    notes._snapshot(repo, username)
    if notes._merge(repo, incoming, username):
        raise notes.NotesError("Backup has changes on another device. Resolve the backup conflict before uploading.")


def pull_repo(root_path, course_name, token, first_name):
    with notes._LOCK:
        try:
            repo, username, branch = _open(root_path, course_name, token)
            with repo:
                _pull(repo, username, branch, token)
            return "Pull Successful: private backup"
        except Exception:
            return "Backup pull failed. Check GitHub connection, private repository access, and local conflicts."


def push_repo(root_path, course_name, token, first_name):
    with notes._LOCK:
        try:
            repo, username, branch = _open(root_path, course_name, token)
            with repo:
                _pull(repo, username, branch, token)
                notes._snapshot(repo, username)
                result = porcelain.push(repo, "origin", [b"HEAD:refs/heads/" + branch.encode()],
                                        outstream=io.BytesIO(), **notes._auth(token))
                if any(result.ref_status.values()):
                    raise notes.NotesError("Remote backup changed.")
            return "Push Successful: private backup"
        except Exception:
            return "Backup push failed. Check GitHub connection, private repository access, and local conflicts."


def sync_course_repo(root_path, course_name, token, first_name):
    result = pull_repo(root_path, course_name, token, first_name)
    if not result.startswith("Pull Successful"):
        return result
    return push_repo(root_path, course_name, token, first_name)
