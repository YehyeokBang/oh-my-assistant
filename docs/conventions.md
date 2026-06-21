# Conventions

Always-on project rules. Referenced from `CLAUDE.md`.

## Git / Commits

- Commit **directly to `main`** (no branch/PR flow).
- Commit & push as the **YehyeokBang** personal account.
  - Verify: `gh auth switch --user YehyeokBang`
  - git config: `Yehyeok Bang` / `qkddpgur318@gmail.com`
- **Commit messages are written in Korean**, in this format:

```
feat: 한글 작업 내용

- 본문 쓸 거 있으면 이렇게
- as is -> to be 이런식으로나.
```

## Security (non-negotiable)

- `claude -p` tool permissions via an `--allowedTools` whitelist, **read-only first**.
- `--dangerously-skip-permissions` only in an isolated environment.
- Secrets in env vars / SSM. **Never** plaintext in code or DB.
