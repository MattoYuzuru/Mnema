FROM busybox:1.36

WORKDIR /www

RUN sh -c 'echo "<h1>Mnema svc2: CI OK</h1>" > index.html'

EXPOSE 8080

CMD ["sh","-c","httpd -f -p 8080 -h /www"]
