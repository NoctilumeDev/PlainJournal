CREATE TABLE distributed_id_worker_lease (
    namespace VARCHAR(64) NOT NULL,
    worker_id INT NOT NULL,
    lease_owner VARCHAR(64) NOT NULL,
    lease_until TIMESTAMP(3) NOT NULL,
    lease_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(3) NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (namespace, worker_id)
);
