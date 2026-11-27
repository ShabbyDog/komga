CREATE TABLE DASHBOARD_DISMISSAL
(
    USER_ID      varchar  NOT NULL,
    KIND         varchar  NOT NULL, -- 'SERIES' or 'READLIST'
    TARGET_ID    varchar  NOT NULL, -- series id (SERIES) or read list id (READLIST)
    DISMISSED_AT datetime NOT NULL,
    PRIMARY KEY (USER_ID, KIND, TARGET_ID)
);
CREATE INDEX idx__dashboard_dismissal__user_kind ON DASHBOARD_DISMISSAL (USER_ID, KIND);
