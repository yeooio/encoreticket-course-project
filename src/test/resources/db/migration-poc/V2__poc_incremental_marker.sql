-- POC-only incremental migration used to prove post-baseline upgrades.
CREATE TABLE flyway_poc_incremental_marker (
    id BIGINT NOT NULL,
    marker VARCHAR(64) NOT NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB;
