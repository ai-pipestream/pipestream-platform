What to do if docker complains about a bad client version

The container client in quarkus is old.  To get a more recent version of docker to recognize it, do this: 

`vim /etc/docker/daemon.json`

```json
{"min-api-version": "1.32"}
```

(adjust JSON to your liking.)