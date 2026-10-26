# Hotel Booking System

A lightweight hotel room booking application built with the Java 17 JDK HTTP server and Maven. It has no application framework or database dependency. The browser UI can check room availability, create reservations, find reservations by email, and cancel them.

## Run locally

```sh
mvn clean package
java -jar target/hotel-booking-system-1.0.0.jar
```

Open [http://localhost:8080](http://localhost:8080). Bookings are written to `data/bookings.tsv` by default. Set `PORT` or `BOOKINGS_FILE` to change the listener port or booking file location.

## Container

```sh
docker build -t hotel-booking-system:local .
docker run --rm -p 8080:8080 -v hotel-booking-data:/data hotel-booking-system:local
```

## Kubernetes

The manifests create a persistent volume claim, a single-replica deployment, and a ClusterIP service. The single replica is intentional because the app uses a local file instead of a shared database.

```sh
kubectl apply -f k8s/
kubectl port-forward service/hotel-booking 8080:8080
```

Then open [http://localhost:8080](http://localhost:8080).

## Jenkins

Configure Jenkins tool installations named `JDK17` and `Maven3`, and ensure the build agent has Docker and `kubectl`. The default pipeline builds and verifies the Maven project, then builds a container image locally.

For deployment, enable `DEPLOY_TO_KUBERNETES`, set `IMAGE_REPOSITORY` to a repository the cluster can pull, and configure Jenkins credentials: a username/password credential for `DOCKER_CREDENTIALS_ID` and a secret-file credential containing the cluster kubeconfig for `KUBECONFIG_CREDENTIALS_ID`. The Kubernetes cluster must have a default StorageClass for the persistent volume claim.

## API

- `GET /api/rooms?checkIn=YYYY-MM-DD&checkOut=YYYY-MM-DD` — list rooms and date-specific availability.
- `POST /api/bookings` — create a booking using form fields `name`, `email`, `roomId`, `checkIn`, and `checkOut`.
- `GET /api/bookings?email=guest@example.com` — look up bookings by guest email.
- `POST /api/bookings/{id}/cancel` — cancel a booking using the matching guest `email`.
- `GET /healthz` — Kubernetes health check.

This starter application does not include user accounts or authentication. Add an authentication layer before exposing booking lookup/cancellation to an untrusted public network.
