#!/bin/sh
# Lambda 핸들러. Lambda Web Adapter(AWS_LAMBDA_EXEC_WRAPPER=/opt/bootstrap)가 이 스크립트로 서버를 띄우고,
# 들어오는 요청을 localhost:8080 HTTP로 넘긴다. Lambda는 메모리 설정만큼 과금하므로 힙을 넉넉히 쓴다
exec java -XX:MaxRAMPercentage=75 -jar "$LAMBDA_TASK_ROOT/app.jar"
