-- Schema, stored procedures and invented customers for the demo.
-- Run against the MariaDB server before recording. The service never runs this itself.

CREATE DATABASE quotes;
USE quotes;

-- No password: the server listens on 127.0.0.1 inside the container and holds invented data only.
CREATE USER 'quotes'@'%';
GRANT ALL PRIVILEGES ON quotes.* TO 'quotes'@'%';

CREATE TABLE customers (
    id        BIGINT PRIMARY KEY,
    full_name VARCHAR(100) NOT NULL,
    phone     VARCHAR(30)  NOT NULL,
    region    VARCHAR(20)  NOT NULL,
    tier      VARCHAR(20)  NOT NULL
);

CREATE TABLE discount_rules (
    tier     VARCHAR(20)   NOT NULL,
    rule     VARCHAR(30)   NOT NULL,
    discount DECIMAL(4, 2) NOT NULL,
    PRIMARY KEY (tier, rule)
);

CREATE TABLE quotes (
    id             VARCHAR(36) PRIMARY KEY,
    quote_number   VARCHAR(30)    NOT NULL,
    customer_id    BIGINT         NOT NULL,
    product        VARCHAR(20)    NOT NULL,
    cover_amount   DECIMAL(12, 2) NOT NULL,
    premium        DECIMAL(12, 2) NOT NULL,
    contact_phone  VARCHAR(30),
    risk_reference VARCHAR(40)    NOT NULL,
    created_at     DATETIME(3)    NOT NULL
);

CREATE TABLE audit_log (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    quote_id   VARCHAR(36)  NOT NULL,
    action     VARCHAR(30)  NOT NULL,
    detail     VARCHAR(200) NOT NULL,
    created_at DATETIME(3)  NOT NULL
);

INSERT INTO customers (id, full_name, phone, region, tier) VALUES
    (101, 'Test Customer One',   '555-0101', 'NORTH', 'GOLD'),
    (102, 'Test Customer Two',   '555-0102', 'SOUTH', 'SILVER'),
    (103, 'Test Customer Three', '555-0103', 'EAST',  'STANDARD');

INSERT INTO discount_rules (tier, rule, discount) VALUES
    ('GOLD',   'LOYALTY',    0.06),
    ('GOLD',   'LOW_CLAIMS', 0.04),
    ('SILVER', 'LOYALTY',    0.05);

DELIMITER $$

-- One IN, five OUT.
CREATE PROCEDURE get_customer(
    IN  p_id        BIGINT,
    OUT p_full_name VARCHAR(100),
    OUT p_phone     VARCHAR(30),
    OUT p_region    VARCHAR(20),
    OUT p_tier      VARCHAR(20),
    OUT p_found     INT)
BEGIN
    SELECT COUNT(*) INTO p_found FROM customers WHERE id = p_id;
    IF p_found > 0 THEN
        SELECT full_name, phone, region, tier INTO p_full_name, p_phone, p_region, p_tier
        FROM customers WHERE id = p_id;
    END IF;
END$$

-- One IN, one OUT, and a result set.
CREATE PROCEDURE get_discount_rules(
    IN  p_tier  VARCHAR(20),
    OUT p_count INT)
BEGIN
    SELECT COUNT(*) INTO p_count FROM discount_rules WHERE tier = p_tier;
    SELECT rule, discount FROM discount_rules WHERE tier = p_tier ORDER BY rule;
END$$

CREATE PROCEDURE create_quote(
    IN  p_id             VARCHAR(36),
    IN  p_quote_number   VARCHAR(30),
    IN  p_customer_id    BIGINT,
    IN  p_product        VARCHAR(20),
    IN  p_cover_amount   DECIMAL(12, 2),
    IN  p_premium        DECIMAL(12, 2),
    IN  p_contact_phone  VARCHAR(30),
    IN  p_risk_reference VARCHAR(40),
    IN  p_created_at     DATETIME(3),
    OUT p_rows           INT)
BEGIN
    INSERT INTO quotes (id, quote_number, customer_id, product, cover_amount, premium,
                        contact_phone, risk_reference, created_at)
    VALUES (p_id, p_quote_number, p_customer_id, p_product, p_cover_amount, p_premium,
            p_contact_phone, p_risk_reference, p_created_at);
    SET p_rows = ROW_COUNT();
END$$

DELIMITER ;
