-- POC-only migration. A production baseline will contain the existing schema
-- and must be reviewed separately before it is applied to encore_ticket.
CREATE TABLE flyway_poc_baseline_marker (
    id BIGINT NOT NULL,
    marker VARCHAR(64) NOT NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB;
