"""Compare source history with the current checkout without changing Git state.

File equality is evidence for review, not a claim of behavioral equivalence.
"""
import argparse
from collections import Counter
from datetime import datetime
import io
import json
from pathlib import Path
import subprocess


project = Path(__file__).resolve().parents[3]


def git(*args):
    return subprocess.check_output(['git', *args], cwd=project).decode('utf-8').strip()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', default='origin/data-agent-multiAgent-Mdl-lyf')
    parser.add_argument('--since', default='2026-10-09T00:00:00+08:00')
    args = parser.parse_args()
    repository = Path(git('rev-parse', '--show-toplevel'))
    source = git('rev-parse', args.source)
    head = git('rev-parse', 'HEAD')
    commits = git('log', source, '--first-parent', '--since=' + args.since,
                  '--reverse', '--format=%H').splitlines()
    rows = []
    paths = set()
    for commit in commits:
        changed = [p for p in git('diff', '--name-only', commit + '^1', commit).splitlines()
                   if '/src/main/resources/static/' not in p]
        paths.update(changed)
        rows.append({'commit': commit, 'subject': git('show', '-s', '--format=%s', commit),
                     'files': changed})
    ordered = sorted(paths)
    requests = ''.join(source + ':' + p + '\n' for p in ordered).encode('utf-8')
    batch = subprocess.run(['git', 'cat-file', '--batch'], input=requests,
                           capture_output=True, check=True, cwd=project)
    stream = io.BytesIO(batch.stdout)
    files = []
    for path in ordered:
        header = stream.readline().decode('utf-8').strip().split()
        if header[-1] == 'missing':
            status = 'source_removed'
        else:
            content = stream.read(int(header[-1]))
            stream.read(1)
            current = repository / path
            if not current.is_file():
                status = 'missing'
            else:
                status = ('same' if current.read_bytes().replace(b'\r\n', b'\n')
                          == content.replace(b'\r\n', b'\n') else 'different')
        files.append({'path': path, 'status': status})
    history = git('log', source, '--since=' + args.since, '--reverse', '--format=%H').splitlines()
    inherited = []
    for commit in history:
        result = subprocess.run(['git', 'merge-base', '--is-ancestor', commit, head], cwd=project)
        if result.returncode == 0:
            inherited.append(commit)
        elif result.returncode != 1:
            raise RuntimeError('Could not check commit ancestry: ' + commit)
    code = [f for f in files if '/src/' in f['path']
            and f['path'].endswith(('.java', '.ts', '.tsx'))]
    report = {'generatedAt': datetime.now().astimezone().isoformat(), 'source': source,
              'head': head, 'branch': git('branch', '--show-current'), 'since': args.since,
              'includesWorkingTree': True, 'historyCommitCount': len(history),
              'firstParentCommitCount': len(commits), 'inheritedCommits': inherited,
              'codeCounts': dict(Counter(f['status'] for f in code)),
              'commits': rows, 'files': files,
              'limitation': 'Equality or presence does not prove integration or behavior.'}
    output = project / 'target/source-integration-audit.json'
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps({k: report[k] for k in ['source', 'head', 'historyCommitCount',
                                            'firstParentCommitCount', 'codeCounts']}))


if __name__ == '__main__':
    main()
