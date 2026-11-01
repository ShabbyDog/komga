CREATE TABLE READ_CONTEXT
(
    USER_ID           varchar  NOT NULL,
    BOOK_ID           varchar  NOT NULL,
    READ_LIST_ID      varchar  NOT NULL,
    PROGRESS_MODIFIED datetime NOT NULL,
    PRIMARY KEY (USER_ID, BOOK_ID)
);
CREATE INDEX idx__read_context__user_read_list ON READ_CONTEXT (USER_ID, READ_LIST_ID);
