-- PostgreSQL Initial Data for RAGStudio V2
-- Entity: UserDO (@TableName="t_user", @TableId=ASSIGN_ID, @TableLogic)

INSERT INTO t_user (id, username, password, role, avatar, create_time, update_time, deleted)
VALUES (2001523723396308993, 'admin', 'admin', 'admin',
        'https://t.alcy.cc/ysmp',
        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0);
