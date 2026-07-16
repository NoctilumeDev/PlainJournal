# Middleware Compatibility POC

This project verifies the application stack against the real local middleware. It is not a business service and will not be copied into production modules.

## Frozen candidate stack

- JDK 17
- Spring Boot 3.5.16
- Spring Cloud 2025.0.3
- Spring Cloud Alibaba 2025.0.0.0
- MyBatis-Plus 3.5.17
- RocketMQ Java Client 5.2.0 over the 5.x gRPC protocol
- MinIO Java SDK 9.0.3

The RocketMQ client is integrated directly behind an infrastructure adapter. The
2.3.6 Spring starter was deliberately not selected because it causes early
BeanPostProcessor initialization warnings on Spring Boot 3.5, while the project
does not need its annotation layer.

## Run

Start the middleware first, then run:

```powershell
./run-poc.ps1
```

The script loads local credentials from `deploy/docker/.env`. Tests clean up temporary records, keys, Nacos resources, objects, and RocketMQ consumers where the client API permits.

The suite performs six real integration checks without middleware mocks:

- Spring application context
- MySQL 8.4 through MyBatis-Plus
- Redis key read/write
- Nacos configuration and service registration
- MinIO upload, signed URL, download, and removal
- RocketMQ gRPC send, receive, and acknowledgement
