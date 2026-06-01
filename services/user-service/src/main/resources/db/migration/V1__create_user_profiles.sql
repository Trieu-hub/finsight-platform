CREATE TABLE user_profiles (
    user_id       BIGINT       NOT NULL,
    full_name     VARCHAR(100),
    phone         VARCHAR(20),
    date_of_birth DATE,
    avatar_url    VARCHAR(255),
    occupation    VARCHAR(100),
    bio           VARCHAR(500),
    created_at    TIMESTAMP,
    updated_at    TIMESTAMP,
    CONSTRAINT pk_user_profiles PRIMARY KEY (user_id)
);
