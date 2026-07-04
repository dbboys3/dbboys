CREATE TABLE t_user (
    user_id    DECIMAL(10,0) PRIMARY KEY,
    username   VARCHAR(32) NOT NULL,
    password   VARCHAR(64) NOT NULL,
    real_name  VARCHAR(20),
    age        SMALLINT CHECK(age > 0),
    email      VARCHAR(100) UNIQUE,
    status     SMALLINT DEFAULT 1,
    create_dt  DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_dt  DATETIME
);