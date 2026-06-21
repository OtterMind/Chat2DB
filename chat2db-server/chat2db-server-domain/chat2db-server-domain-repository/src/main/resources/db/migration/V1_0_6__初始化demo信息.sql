INSERT INTO DATA_SOURCE (GMT_CREATE, GMT_MODIFIED, ALIAS, URL, USER_NAME, PASSWORD, TYPE, USER_ID, HOST, PORT, SSH,JDBC)
VALUES (CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'DEMO@db.sqlgpt.cn', 'jdbc:mysql://db.sqlgpt.cn:3306/DEMO', 'demo', 'kok39AYoOSM=', 'MYSQL', 0, 'db.sqlgpt.cn', '3306', '{"use":false}', '8.0');

INSERT INTO DASHBOARD (ID, GMT_CREATE, GMT_MODIFIED, NAME, DESCRIPTION, `SCHEMA`, DELETED, USER_ID)
VALUES (1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, '学生成绩分析', '学生成绩分析', '[[1],[2],[3]]', 'N', 0);

INSERT INTO CHART (ID, GMT_CREATE, GMT_MODIFIED,  `SCHEMA`, DATA_SOURCE_ID, DATABASE_NAME, DDL, DELETED, USER_ID)
VALUES (1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP,  '{"chartType":"Column","xAxis":"name","yAxis":"total_score"}', 1, 'DEMO', 'SELECT s.name, sc.chinese_score, sc.math_score, sc.english_score, sc.science_score, sc.humanities_score,
(sc.chinese_score + sc.math_score + sc.english_score + sc.science_score + sc.humanities_score) AS total_score
FROM student s
JOIN score sc ON s.id = sc.student_id', 'N', 0);

INSERT INTO CHART (ID, GMT_CREATE, GMT_MODIFIED,  `SCHEMA`, DATA_SOURCE_ID, DATABASE_NAME, DDL, DELETED, USER_ID)
VALUES (2, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, '{"chartType":"Pie","xAxis":"grade"}', 1, 'DEMO', 'SELECT s.name,
       score.chinese_score,
       score.math_score,
       score.english_score,
       score.science_score,
       score.humanities_score,
       (score.chinese_score + score.math_score + score.english_score + score.science_score + score.humanities_score) AS total_score,
       CASE
           WHEN (score.chinese_score + score.math_score + score.english_score + score.science_score + score.humanities_score) < 630 THEN "D"
           WHEN (score.chinese_score + score.math_score + score.english_score + score.science_score + score.humanities_score) >= 630 AND (score.chinese_score + score.math_score + score.english_score + score.science_score + score.humanities_score) <= 735 THEN "C"
           WHEN (score.chinese_score + score.math_score + score.english_score + score.science_score + score.humanities_score) > 840 THEN "A"
           ELSE "B"
       END AS grade
FROM score
JOIN student s ON score.student_id = s.id', 'N', 0);

INSERT INTO CHART (ID, GMT_CREATE, GMT_MODIFIED,  `SCHEMA`, DATA_SOURCE_ID, DATABASE_NAME, DDL, DELETED, USER_ID)
VALUES (3, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP,  '{"chartType":"Line","xAxis":"name","yAxis":"chinese_score"}', 1, 'DEMO', 'SELECT s.name, sc.chinese_score, sc.math_score, sc.english_score, sc.science_score, sc.humanities_score,
(sc.chinese_score + sc.math_score + sc.english_score + sc.science_score + sc.humanities_score) AS total_score
FROM student s
JOIN score sc ON s.id = sc.student_id', 'N', 0);

INSERT INTO DASHBOARD_CHART_RELATION (GMT_CREATE, GMT_MODIFIED, DASHBOARD_ID, CHART_ID)
VALUES (CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 1, 1);
INSERT INTO DASHBOARD_CHART_RELATION (GMT_CREATE, GMT_MODIFIED, DASHBOARD_ID, CHART_ID)
VALUES (CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 1, 2);
INSERT INTO DASHBOARD_CHART_RELATION (GMT_CREATE, GMT_MODIFIED, DASHBOARD_ID, CHART_ID)
VALUES (CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 1, 3);