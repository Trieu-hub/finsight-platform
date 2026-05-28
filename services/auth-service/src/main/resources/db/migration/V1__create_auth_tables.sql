CREATE TABLE roles (
                       id BIGINT PRIMARY KEY AUTO_INCREMENT,
                       name VARCHAR(50) NOT NULL UNIQUE
);

CREATE TABLE users (
                       id BIGINT PRIMARY KEY AUTO_INCREMENT,
                       email VARCHAR(255) NOT NULL UNIQUE,
                       password VARCHAR(255) NOT NULL,
                       enabled BOOLEAN DEFAULT TRUE,
                       role_id BIGINT,
                       created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

                       CONSTRAINT fk_user_role
                           FOREIGN KEY (role_id)
                               REFERENCES roles(id)
);

CREATE TABLE refresh_tokens (
                                id BIGINT PRIMARY KEY AUTO_INCREMENT,
                                token VARCHAR(500) NOT NULL,
                                user_id BIGINT,
                                expiry_date TIMESTAMP,

                                CONSTRAINT fk_refresh_user
                                    FOREIGN KEY (user_id)
                                        REFERENCES users(id)
);