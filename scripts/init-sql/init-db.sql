CREATE TABLE users (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    username TEXT NOT NULL UNIQUE,
    password TEXT NOT NULL,
    email TEXT NOT NULL UNIQUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO users (username, password, email) VALUES ('admin', 'admin123', 'admin@example.com');
INSERT INTO users (username, password, email) VALUES ('user1', 'password1', 'user1@example.com');
INSERT INTO users (username, password, email) VALUES ('user2', 'password2', 'user2@example.com');