import os
import requests
import re
import traceback
from dulwich import porcelain
from dulwich.repo import Repo

def sanitize_repo_name(name):
    # Ensure consistency: replace EVERYTHING except letters and numbers with hyphens
    sanitized = re.sub(r'[^a-zA-Z0-9]', '-', name)
    sanitized = re.sub(r'-+', '-', sanitized)
    return sanitized.strip('-')

def get_repo_details(root_path, course_name, token):
    repo_name = sanitize_repo_name(course_name)
    local_path = os.path.join(root_path, repo_name)

    if not os.path.exists(local_path):
        os.makedirs(local_path, exist_ok=True)

    headers = {
        "Authorization": f"token {token}",
        "Accept": "application/vnd.github.v3+json"
    }

    user_resp = requests.get("https://api.github.com/user", headers=headers, timeout=10)
    if user_resp.status_code != 200:
        raise Exception(f"Auth Error: {user_resp.status_code}")

    username = user_resp.json()["login"]
    auth_repo_url = f"https://{username}:{token}@github.com/{username}/{repo_name}.git"

    check_resp = requests.get(f"https://api.github.com/repos/{username}/{repo_name}", headers=headers, timeout=10)
    if check_resp.status_code == 404:
        create_resp = requests.post(
            "https://api.github.com/user/repos",
            headers=headers,
            json={"name": repo_name, "private": False},
            timeout=10
        )
        if create_resp.status_code != 201 and "already exists" not in create_resp.text:
            raise Exception(f"Create Repo Error: {create_resp.status_code}")

    git_dir = os.path.join(local_path, ".git")
    if not os.path.exists(git_dir):
        repo = Repo.init(local_path)
        config = repo.get_config()
        config.set((b"remote", b"origin"), b"url", auth_repo_url.encode('utf-8'))
        config.set((b"remote", b"origin"), b"fetch", b"+refs/heads/*:refs/remotes/origin/*")
        config.write_to_path()
    else:
        repo = Repo(local_path)

    return repo, repo_name, username, local_path

def pull_repo(root_path, course_name, token, first_name):
    try:
        repo, repo_name, username, local_path = get_repo_details(root_path, course_name, token)
        print(f"Pulling from origin for {repo_name}...")

        try:
            porcelain.fetch(repo, "origin")
        except Exception as e:
            print(f"Fetch failed: {e}")
            return f"Fetch failed: {str(e)}"

        try:
            refs = repo.get_refs()
            remote_main = b"refs/remotes/origin/main"
            local_main = b"refs/heads/main"

            if remote_main not in refs:
                return "Remote 'main' not found. Repository might be empty."

            # If local main doesn't exist, create it from remote main and set HEAD
            if local_main not in refs:
                repo[local_main] = repo[remote_main]
                repo.refs.set_symbolic_ref(b"HEAD", local_main)
                # We need to perform a hard reset to actually write the files to the filesystem
                porcelain.reset(repo, "hard", remote_main)
                return f"Pull Successful: {repo_name} (Initialized)"

            # Regular pull for existing local repository
            porcelain.pull(repo, "origin", b"refs/heads/main")
            return f"Pull Successful: {repo_name}"
        except Exception as e:
            traceback.print_exc()
            return f"Pull Error: {str(e)}"

    except Exception as e:
        traceback.print_exc()
        return f"Error: {str(e)}"

def push_repo(root_path, course_name, token, first_name):
    try:
        repo, repo_name, username, local_path = get_repo_details(root_path, course_name, token)

        # Create README only if repo is empty and no files exist
        readme_path = os.path.join(local_path, "README.md")
        if not os.path.exists(readme_path):
            has_files = False
            for r, d, f in os.walk(local_path):
                # Ignore hidden directories (starting with '.')
                d[:] = [dirname for dirname in d if not dirname.startswith('.')]
                # Filter out hidden files
                visible_files = [file for file in f if not file.startswith('.')]
                if visible_files:
                    has_files = True
                    break
            if not has_files:
                with open(readme_path, "w") as f:
                    f.write(f"# {course_name}\nNotes for {course_name}")

        # Add all non-hidden files recursively
        added_paths = []
        for root, dirs, files in os.walk(local_path):
            # Ignore hidden directories
            dirs[:] = [d for d in dirs if not d.startswith('.')]
            for f in files:
                # Ignore hidden files
                if f.startswith('.'):
                    continue
                abs_p = os.path.join(root, f)
                rel_p = os.path.relpath(abs_p, local_path)
                added_paths.append(rel_p.encode('utf-8'))

        if added_paths:
            porcelain.add(repo, added_paths)

        status = porcelain.status(repo)
        has_staged = any([status.staged['add'], status.staged['modify'], status.staged['delete']])

        if has_staged:
            author = f"{first_name} <{username}@github.com>".encode('utf-8')
            porcelain.commit(repo, message=f"Push by {first_name}".encode('utf-8'), author=author)
            print("Committed changes")

        try:
            repo.head()
        except KeyError:
            return "Nothing to push (no commits yet)"

        print(f"Pushing to origin for {repo_name}...")
        porcelain.push(repo, "origin", [b"HEAD:refs/heads/main"])
        return f"Push Successful: {repo_name}"
    except Exception as e:
        traceback.print_exc()
        return f"Push Error: {str(e)}"

def sync_course_repo(root_path, course_name, token, first_name):
    pull_res = pull_repo(root_path, course_name, token, first_name)
    push_res = push_repo(root_path, course_name, token, first_name)
    return f"Sync: {pull_res} | {push_res}"
