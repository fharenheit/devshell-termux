cd ~
git config --global user.name "Demo User"; git config --global user.email demo@example.com; git config --global init.defaultBranch main
rm -rf webapp; mkdir -p webapp/src && cd webapp && git init -q
printf '# webapp\n' > README.md; git add .; git commit -qm "Initial commit"
printf 'print("hello")\n' > src/app.py; git add .; git commit -qm "Add app entry point"
printf 'PORT=8080\n' > .env.example; git add .; git commit -qm "Add example environment file"
printf 'def health():\n    return "ok"\n' > src/health.py; git add .; git commit -qm "Add health check endpoint"
git checkout -qb feature/login; printf 'def login(): pass\n' > src/auth.py; git add .; git commit -qm "Add login handler"
git checkout -q main; printf 'print("hello, world")\n' > src/app.py; echo "node_modules/" > .gitignore
cd ~
mkdir -p ~/.ssh; [ -f ~/.ssh/id_ed25519 ] || ssh-keygen -q -t ed25519 -N "" -C "demo@termux" -f ~/.ssh/id_ed25519
