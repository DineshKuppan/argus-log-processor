# Argus on Kubernetes

Several Argus instances on Kafka as the WAL ([`P6_PLAN.md`](../../docs/architecture/P6_PLAN.md) D8–D13), scaled on the sinks' consumer lag.

| File | What |
|---|---|
| `base/statefulset.yaml` | One pod per instance. The pod name (`argus-0`, …) is `argus.instance.id` (D8). Each pod has its own volume for spools, checkpoints and staging. Probes are `/readyz` and `/livez`; `terminationGracePeriodSeconds` is 120 (D12). |
| `base/services.yaml` | A headless service (stable pod DNS names) and a ClusterIP service for the ingress |
| `base/ingress.yaml` | NGINX ingress with `upstream-hash-by: "$uri"`, so a stream's pushes go to one pod (D12) |
| `base/keda-scaledobject.yaml` | KEDA Kafka scaler on the `argus-archive` and `argus-hot` groups' lag; `maxReplicaCount` 12, the partition count (D9, D13) |
| `base/configmap.yaml` | `application-k8s.properties`, mounted at `/config` |
| `hpa-prometheus.yaml` | Without KEDA: an HPA on `argus_kafka_lag_records` through the Prometheus Adapter |
| `kind/` | The `kind` check: one-broker Kafka, S3Mock and Redis in the cluster, small pods, 6 partitions |
| `../Dockerfile` | The image: the Boot jar on `eclipse-temurin:17-jre`, heap at 60% of the memory limit |

## Sizing and limits

- **Pod size.** Each pod is sized for the single-node target: 2 CPUs and 4GiB, giving a heap of about 2.4GB (guardrail 5). Its 100Gi volume holds:
  - spools: up to `max-spool-bytes`, 2GB;
  - archive staging: a few open segments;
  - the local WAL, only until `WalForwarder` has sent it to Kafka.
- **Parallelism.** Each consumer group runs at most as many consumers as the topic has partitions (12). Keep `maxReplicaCount` at or below that. To go further, add partitions while the instances are stopped, because a tenant's tail ring id is its partition.
- **Scale-down drains.** On SIGTERM (`InstanceDrain`), in this order:
  1. readiness goes down;
  2. new pushes get 503 + `Retry-After: 1`;
  3. pushes already in progress finish;
  4. every spool is ingested into Kafka;
  5. the consumers commit and leave their groups.

  This is bounded by `argus.instance.drain-timeout` (90s, under the 120s grace period). A spool that isn't drained in time stays on the pod's volume and is ingested when that pod returns.
- **Volumes on scale-down.** A StatefulSet keeps a removed pod's volume, so `argus-2`'s spools are still there when `argus-2` comes back. Delete a volume only after its pod drained cleanly; the drain logs one line, `Instance drain finished … spoolsDrained=true`.
- **Affinity is best effort.** While pods are added or removed, the hash moves some streams. A keyed retry that lands on another pod isn't recognised as a repeat, so it is stored twice (at-least-once, guardrail 3).
- **Jobs stay where they started.** File and export jobs run on the pod that accepted them. Their ids name that pod (`argus-1:…`), and another pod answers 404 naming it.
- **Suppression across pods needs Redis** (`argus.suppression.store=redis`, D11). With the local store, each pod suppresses only its own copies.
- **Credentials don't go in the ConfigMap.** Put S3/GCS and Kafka SASL credentials in a Secret or the workload identity. API key *hashes* are safe in the ConfigMap.

## The kind check

Run with Docker Desktop at 4 CPUs and 8GB:

```bash
kind create cluster --name argus
helm repo add kedacore https://kedacore.github.io/charts
helm install keda kedacore/keda --namespace keda --create-namespace --version 2.20.2
mvn -P ci package -DskipTests && docker build -f deploy/Dockerfile -t argus:dev .
for i in argus:dev apache/kafka:3.8.1 adobe/s3mock:4.1.1 redis:7.2-alpine; do kind load docker-image $i --name argus; done
kubectl apply -k deploy/k8s/kind
kubectl -n argus rollout status statefulset/argus
```

What the check does (results are in `P6_PLAN.md` §9):
1. It makes the archive fall behind by scaling `s3mock` to 0 while pushing. The `argus-archive` lag passes the overlay's 20,000-record threshold, and KEDA scales `argus` from 1 to 3.
2. It brings `s3mock` back. The lag drains, and after the cooldown KEDA scales back to 1. Each removed pod logs its drain line.

Delete the cluster afterwards with `kind delete cluster --name argus`.
