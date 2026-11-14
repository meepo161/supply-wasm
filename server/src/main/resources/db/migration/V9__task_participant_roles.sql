-- Разрешить одному пользователю иметь несколько ролей в задаче.
ALTER TABLE task_participants
    DROP CONSTRAINT task_participants_pkey;

ALTER TABLE task_participants
    ADD PRIMARY KEY (task_id, user_id, role);
