"""Inspect localhost availability without exposing login tokens."""
import json
import socket
import urllib.request
import urllib.error

for port in (8080, 8082, 8081, 8083):
    try:
        with urllib.request.urlopen(f"http://127.0.0.1:{port}/actuator/health", timeout=4) as r:
            print(port, r.status, r.read().decode()[:300])
    except Exception as e:
        print(port, type(e).__name__, str(e)[:160])
for user in ("bob", "alice"):
    try:
        request=urllib.request.Request("http://127.0.0.1:8080/api/auth/login",
            data=json.dumps({"username":user,"password":user}).encode(),
            headers={"Content-Type":"application/json"})
        with urllib.request.urlopen(request,timeout=10) as response:
            body=json.load(response)
            print("login",user,response.status,"userId",body.get("userId"),"roles",body.get("roles"))
    except Exception as e:
        print("login",user,type(e).__name__,str(e)[:150])
