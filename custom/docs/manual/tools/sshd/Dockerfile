FROM alpine:3.20
RUN apk add --no-cache openssh python3 mosh-server tmux && ssh-keygen -A \
 && adduser -D -s /bin/sh demo && echo 'demo:demo1234' | chpasswd \
 && sed -i 's/^#\?PasswordAuthentication.*/PasswordAuthentication yes/; s/^AllowTcpForwarding.*/AllowTcpForwarding yes/' /etc/ssh/sshd_config \
 && echo 'Welcome to dev-server' > /etc/motd
COPY photo1.jpg photo2.jpg /home/demo/photos/
RUN cd /home/demo && mkdir -p projects/webapp/src docs logs \
 && printf '#!/usr/bin/env python3\n"""Simple web server."""\nimport http.server\n\nPORT = 8080\n\n\ndef main():\n    handler = http.server.SimpleHTTPRequestHandler\n    with http.server.HTTPServer(("", PORT), handler) as httpd:\n        print(f"Serving on port {PORT}")\n        httpd.serve_forever()\n\n\nif __name__ == "__main__":\n    main()\n' > projects/webapp/src/server.py \
 && printf '# webapp\n\nDemo project.\n\n- run: `python3 src/server.py`\n' > projects/webapp/README.md \
 && printf '#!/bin/sh\nset -e\necho "deploying..."\nrsync -av src/ server:/srv/app/\n' > projects/webapp/deploy.sh && chmod 755 projects/webapp/deploy.sh \
 && printf '{\n  "name": "webapp",\n  "version": "1.0.0",\n  "port": 8080\n}\n' > projects/webapp/config.json \
 && printf '2026-10-01 meeting notes\n- release v1.0\n' > docs/notes.txt \
 && printf 'INFO server started\nINFO GET / 200\n' > logs/app.log \
 && printf 'export PS1="\\u@\\h:\\w\\$ "\n' > .profile \
 && chown -R demo:demo /home/demo
EXPOSE 22
CMD ["/usr/sbin/sshd","-D","-e"]
