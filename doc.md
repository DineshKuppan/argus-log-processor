
https://mermaid.live/edit#pako:eNqFk0uP2jAUhf-KZbYBQd64UiUGGJWKtmgy6qJJF258HaImduQ4MzCI_94k5lWpQ6Ms7rW_c2yfxAecSgaY4OFwmAid6wIImqmsqdFaZmijZAp1nYsMRaBe8hTQpzzbojW8QJGIXsQL-ZpuqdLoeZEI1D6zuNM-5gWglaga_ZMQkncFGg4_ood-dkNVDaqbqfrKKB96Yn7Wa1B1h_C-NMi8RxbxLMsUZFRLg9BLa7BFjy3jp2X0jGabFXqCupKiBkNXeSIMuOzBx0k8X69Q1JQlVfsOkY1ud_wXY8efo29f0XJXSaXfYZx4Hn3_B5KItKB1vQCOTBTtmQqCBmkKHudWrZX83UY_cJwp5eNTP3zNmd4SZFe7DzcGJjHjMGAuMEavBnZIA9e7b2DyPBlwzp2UXQ04Tyfj4L7BNe3zOcBmU-5cXXzu2unkPy5VfpZzDt4v92YTLICJe19uor04hCy4zYGljvdODtjCJaiS5qz98Q_d50uw3kIJCSZtyYDTptAJTsSxRWmjZbQXKSZaNWBhJZtsiwmnRd12TcWohkVOM0XLy2hFxQ8py7OkbTE54B0mzng6Cv3QDmzf88bTwLfwHpPAGfm2Ow09v32D0J4cLfzW68ejMPAsDCxvs_5irmp_Yy2cqW7_pwUVCAZqLhuh20Xc8PgH8FBBDQ


## Parse Logs

curl --location 'http://localhost:9000/api/v1/logs/parse?file=src%2Fmain%2Fresources%2Flogs%2Fapp.json'

### Response

[
    {
        "timestamp": "2025-09-19T22:21:35.306482Z",
        "level": "ERROR",
        "message": "PUT /api/products HTTP/1.1 - Deprecated API used",
        "meta": {
            "timestamp": "2025-09-19T22:21:35.306482Z",
            "level": "ERROR",
            "message": "PUT /api/products HTTP/1.1 - Deprecated API used",
            "user": "frank",
            "latency_ms": 714
        }
    },
    {
        "timestamp": "2025-09-19T17:28:12.321927Z",
        "level": "INFO",
        "message": "PUT /api/status HTTP/1.1",
        "meta": {
            "timestamp": "2025-09-19T17:28:12.321927Z",
            "level": "INFO",
            "message": "PUT /api/status HTTP/1.1",
            "user": "eve",
            "latency_ms": 408
        }
    }
]

## Filter logs

curl --location 'http://localhost:9000/api/v1/logs/filter' \
--header 'Content-Type: application/json' \
--data '{
  "file": "src/main/resources/logs/app.json",
  "from": "2025-09-19T23:00:00Z",
  "to": "2025-09-19T23:29:59.689027Z",
  "levels": ["ERROR"],
  "regex": "/api/orders"
}'

### Response

[
    {
        "timestamp": "2025-09-19T23:17:10.340412Z",
        "level": "ERROR",
        "message": "PUT /api/orders HTTP/1.1 - Validation error",
        "meta": {
            "timestamp": "2025-09-19T23:17:10.340412Z",
            "level": "ERROR",
            "message": "PUT /api/orders HTTP/1.1 - Validation error",
            "user": "alice",
            "latency_ms": 886
        }
    }
]

## Aggregate V1 Logs

curl --location 'http://localhost:9000/api/v1/logs/aggregate' \
--header 'Content-Type: application/json' \
--data '{
  "file": "src/main/resources/logs/app.json",
  "topN": 5
}'

### Response

{
    "errorRate": [
        {
            "errors": 1,
            "errorRate": 0.5,
            "total": 2,
            "minute": "2025-09-19T04:50:00Z"
        },
        {
            "errors": 1,
            "errorRate": 0.2,
            "total": 5,
            "minute": "2025-09-19T04:51:00Z"
        },
        {
            "errors": 1,
            "errorRate": 0.3333333333333333,
            "total": 3,
            "minute": "2025-09-19T04:52:00Z"
        }
    ],
    "topEndpoints": [
        {
            "/api/status": 6296
        },
        {
            "/api/users": 6288
        },
        {
            "/api/products": 6222
        },
        {
            "/api/orders": 6194
        }
    ],
    "byLevel": {
        "ERROR": 6204,
        "INFO": 6263,
        "DEBUG": 6160,
        "WARN": 6373
    }
}

## Aggregate V2 Logs

curl --location 'http://localhost:9000/api/v1/logs/aggregateV2' \
--header 'Content-Type: application/json' \
--data '{
  "file": "src/main/resources/logs/app.json",
  "topN": 5
}'

### Response
{
    "totalLogs": 25000,
    "byLevel": {
        "ERROR": 6204,
        "INFO": 6263,
        "DEBUG": 6160,
        "WARN": 6373
    },
    "topEndpoints": [
        {
            "/api/status": 6296
        },
        {
            "/api/users": 6288
        },
        {
            "/api/products": 6222
        },
        {
            "/api/orders": 6194
        }
    ],
    "errorRate": [
        {
            "total": 2,
            "errorRate": 0.5,
            "errors": 1,
            "minute": "2025-09-19T04:50:00Z"
        },
        {
            "total": 5,
            "errorRate": 0.2,
            "errors": 1,
            "minute": "2025-09-19T04:51:00Z"
        }
    ]
}

## Export to CSV endpoint

curl --location 'http://localhost:9000/api/v1/logs/export/csv' \
--header 'Content-Type: application/json' \
--data '{
  "file": "src/main/resources/logs/app.json"
}'

### Response
"minute","total","errors","errorRate"
"2025-09-19T04:50:00Z","2","1","0.5"
"2025-09-19T04:51:00Z","5","1","0.2"
"2025-09-19T04:52:00Z","3","1","0.3333333333333333"
"2025-09-19T04:53:00Z","3","1","0.3333333333333333"
"2025-09-19T04:54:00Z","10","3","0.3"
"2025-09-19T04:55:00Z","16","7","0.4375"
"2025-09-19T04:56:00Z","15","2","0.13333333333333333"
"2025-09-19T04:57:00Z","13","5","0.38461538461538464"
"2025-09-19T04:58:00Z","18","5","0.2777777777777778"
"2025-09-19T04:59:00Z","9","3","0.3333333333333333"

## CLI Summary

curl --location 'http://localhost:9000/api/v1/logs/cli-summary' \
--header 'Content-Type: application/json' \
--data '{
  "file": "src/main/resources/logs/app.json"
}'

### Response for CLI Summary (Log Level Type - ERROR)

Total    Level    Time Range
210      ERROR    logs between 2025-09-20T04:00:00Z–2025-09-20T04:00:00Z:59
235      ERROR    logs between 2025-09-20T03:00:00Z–2025-09-20T03:00:00Z:59
220      ERROR    logs between 2025-09-19T19:00:00Z–2025-09-19T19:00:00Z:59
220      ERROR    logs between 2025-09-19T18:00:00Z–2025-09-19T18:00:00Z:59
207      ERROR    logs between 2025-09-19T09:00:00Z–2025-09-19T09:00:00Z:59
189      ERROR    logs between 2025-09-20T00:00:00Z–2025-09-20T00:00:00Z:59
226      ERROR    logs between 2025-09-20T07:00:00Z–2025-09-20T07:00:00Z:59
203      ERROR    logs between 2025-09-19T08:00:00Z–2025-09-19T08:00:00Z:59
250      ERROR    logs between 2025-09-19T07:00:00Z–2025-09-19T07:00:00Z:59
217      ERROR    logs between 2025-09-20T06:00:00Z–2025-09-20T06:00:00Z:59
172      ERROR    logs between 2025-09-20T08:00:00Z–2025-09-20T08:00:00Z:59
232      ERROR    logs between 2025-09-20T02:00:00Z–2025-09-20T02:00:00Z:59
208      ERROR    logs between 2025-09-20T05:00:00Z–2025-09-20T05:00:00Z:59
224      ERROR    logs between 2025-09-20T01:00:00Z–2025-09-20T01:00:00Z:59
225      ERROR    logs between 2025-09-19T21:00:00Z–2025-09-19T21:00:00Z:59
29       ERROR    logs between 2025-09-19T04:00:00Z–2025-09-19T04:00:00Z:59
248      ERROR    logs between 2025-09-19T13:00:00Z–2025-09-19T13:00:00Z:59
212      ERROR    logs between 2025-09-19T12:00:00Z–2025-09-19T12:00:00Z:59
209      ERROR    logs between 2025-09-19T05:00:00Z–2025-09-19T05:00:00Z:59
220      ERROR    logs between 2025-09-19T06:00:00Z–2025-09-19T06:00:00Z:59
240      ERROR    logs between 2025-09-19T14:00:00Z–2025-09-19T14:00:00Z:59
258      ERROR    logs between 2025-09-19T10:00:00Z–2025-09-19T10:00:00Z:59
210      ERROR    logs between 2025-09-19T11:00:00Z–2025-09-19T11:00:00Z:59
241      ERROR    logs between 2025-09-19T15:00:00Z–2025-09-19T15:00:00Z:59
230      ERROR    logs between 2025-09-19T16:00:00Z–2025-09-19T16:00:00Z:59
216      ERROR    logs between 2025-09-19T17:00:00Z–2025-09-19T17:00:00Z:59
203      ERROR    logs between 2025-09-19T20:00:00Z–2025-09-19T20:00:00Z:59
205      ERROR    logs between 2025-09-19T22:00:00Z–2025-09-19T22:00:00Z:59
245      ERROR    logs between 2025-09-19T23:00:00Z–2025-09-19T23:00:00Z:59
