import requests
import json
import os
from dotenv import load_dotenv

load_dotenv("../whatsapp-bot/.env")
url = "http://192.168.1.249:3000/osp/endpoint.json"
try:
    resp = requests.get(url, timeout=5)
    print("Endpoint JSON:", resp.json())
except Exception as e:
    print("Error:", e)

url_query = "http://192.168.1.249:3000/osp/query"
headers = {"x-api-token": os.getenv("API_TOKEN"), "Content-Type": "application/json"}
try:
    resp = requests.post(url_query, headers=headers, json={"query": "test query"}, timeout=15)
    print("Query Response:", resp.json())
except Exception as e:
    print("Query Error:", e)

