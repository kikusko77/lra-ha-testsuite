# lra-ha-testsuite

This project runs a **Quarkus LRA participant** + **Narayana LRA coordinator** locally using **Docker Desktop Kubernetes**, and executes integration tests against them via **port-forwarding**.

---

## Prerequisites (one-time)

- Docker Desktop (Kubernetes enabled)
- `kubectl`
- Java + Maven
- Local Docker registry running at `localhost:5001`

If you don’t have the registry yet:

```bash
docker run -d --restart=always -p 5001:5000 --name registry registry:2
```

---

## 0) Verify Kubernetes is reachable (one-time)

```bash
kubectl get nodes
```

You should see all nodes in **Ready** state.

---

## 1) Deploy coordinator + participant

From the repository root:

```bash
kubectl apply -f k8s-coordinator.yaml
kubectl apply -f k8s.yaml
```

Wait until both pods are running:

```bash
kubectl get pods -w
```

Expected:

- `lra-coordinator-...  1/1 Running`
- `lra-participant-...  1/1 Running`

---

## 2) Ensure participant knows where the coordinator is

The participant must have this environment variable:

```
QUARKUS_LRA_COORDINATOR_URL=http://lra-coordinator:8090/lra-coordinator
```

Check:

```bash
kubectl get deploy lra-participant -o=jsonpath='{.spec.template.spec.containers[0].env}{"\n"}'
```

If missing, set it once:

```bash
kubectl set env deploy/lra-participant \
  QUARKUS_LRA_COORDINATOR_URL=http://lra-coordinator:8090/lra-coordinator

kubectl rollout restart deploy/lra-participant
kubectl rollout status deploy/lra-participant
```

---

## 3) Port-forward the coordinator (Terminal 1)

```bash
kubectl port-forward svc/lra-coordinator 8090:8090
```

Leave this terminal running.

Quick check (in another terminal):

```bash
curl -i http://localhost:8090/lra-coordinator
```

---

## 4) Port-forward the participant (Terminal 2)

```bash
kubectl port-forward svc/lra-participant 8081:8081
```

Leave this terminal running.

Quick check:

```bash
curl -i http://localhost:8081/participant2/start-lra
```

Expected: `200 OK` + LRA id in response body.

---

## 5) Run the integration test (Terminal 3)

```bash
COORDINATOR_BASE_URL=http://localhost:8090/lra-coordinator \
PARTICIPANT_BASE_URL=http://localhost:8081 \
./mvnw -Dtest=LraIT test
```

That’s it.

---

# What to do when things change

## 🔁 When you change Kubernetes YAML only

Examples:

- env vars
- probes
- ports
- resource limits

Steps:

```bash
kubectl apply -f k8s.yaml
kubectl apply -f k8s-coordinator.yaml
```

If needed, force restart:

```bash
kubectl rollout restart deploy/lra-participant
kubectl rollout restart deploy/lra-coordinator
```

No Docker rebuild needed.

---

## 🧱 When you change participant Java code

Examples:

- REST endpoints
- LRA annotations
- logic in `LRAParticipant`

Steps (important):

1) Build the JAR

```bash
./mvnw clean package -DskipTests
```

2) Build the Docker image

```bash
docker build -t lra-participant:1.0.3.Final-SNAPSHOT .
```

3) Push to local registry

```bash
docker tag lra-participant:1.0.3.Final-SNAPSHOT \
  localhost:5001/lra-participant:1.0.3.Final-SNAPSHOT

docker push localhost:5001/lra-participant:1.0.3.Final-SNAPSHOT
```

4) Restart the deployment

```bash
kubectl rollout restart deploy/lra-participant
kubectl rollout status deploy/lra-participant
```

💡 You do not need to re-apply YAML unless the YAML changed.

---

## 🧱 When you change coordinator code or image

Same pattern as participant, just different image name:

```bash
docker build -t local-coordinator:latest .
docker tag local-coordinator:latest localhost:5001/local-coordinator:latest
docker push localhost:5001/local-coordinator:latest

kubectl rollout restart deploy/lra-coordinator
kubectl rollout status deploy/lra-coordinator
```

---

## 🔄 When port-forward breaks

This happens after rollouts.

Fix:

- Stop port-forward (`Ctrl+C`)
- Start it again (steps 3 and 4)

---

## 🧹 Cleanup (optional)

Remove everything from the cluster:

```bash
kubectl delete -f k8s.yaml
kubectl delete -f k8s-coordinator.yaml
```

---

## Mental model

- Docker → builds images
- Kubernetes Deployment → runs containers
- Service → stable networking
- port-forward → temporary bridge to your laptop
- JUnit test → external client calling real services
