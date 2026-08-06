# 운영 서버(EC2) 설정

이 디렉터리는 **운영 EC2 에 수동으로 설치하는 설정 파일들의 원본**이다.
배포 워크플로([.github/workflows/deploy.yml](../.github/workflows/deploy.yml))는 jar 업로드와
EnvironmentFile 기록만 하고 **이 파일들을 서버로 복사하지 않는다.** 서버에 반영하려면 직접 설치해야 하고,
반대로 서버에서 고친 내용은 여기에도 같이 반영해야 두 벌이 어긋나지 않는다.

| 파일 | 설치 위치 |
|---|---|
| `wishconnect.service` | `/etc/systemd/system/wishconnect.service` |
| `wishconnect.logrotate` | `/etc/logrotate.d/wishconnect` |
| `wishconnect.env.example` | `/etc/wishconnect/wishconnect.env` (실제 값은 배포 워크플로가 기록, root:600) |

## 설치

```bash
sudo cp wishconnect.service /etc/systemd/system/wishconnect.service
sudo systemd-analyze verify /etc/systemd/system/wishconnect.service
sudo systemctl daemon-reload && sudo systemctl restart wishconnect

sudo cp wishconnect.logrotate /etc/logrotate.d/wishconnect
sudo logrotate -d /etc/logrotate.d/wishconnect   # -d: 시뮬레이션만
```

## 스왑 (파일로 관리되지 않는 설정)

t3.small 은 메모리 1.9GB 라 여유가 크지 않다. 스왑이 없으면 메모리가 몰릴 때
커널 OOM killer 가 **가장 큰 프로세스인 java 를 즉시 죽인다.**
실제로 2026-07-28, 2026-08-03 두 번 이렇게 죽었고, 8/3 건은 앱이 아니라
cron 작업이 메모리를 요구하면서 발생했다. 스왑은 "죽는 대신 느려지는" 안전망이다.

```bash
sudo fallocate -l 2G /swapfile
sudo chmod 600 /swapfile
sudo mkswap /swapfile && sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab          # 재부팅 후 유지
echo 'vm.swappiness=10' | sudo tee /etc/sysctl.d/99-swappiness.conf # 평소엔 안 쓰고 비상시에만
sudo sysctl -p /etc/sysctl.d/99-swappiness.conf
```

`swappiness` 기본값 60 은 여유가 있어도 적극적으로 스왑을 써서 평상시 응답이 느려진다.
10 으로 낮춰 진짜 몰릴 때만 쓰이게 한다.

## 확인

```bash
sudo systemctl status wishconnect
curl -s localhost:8080/actuator/health          # {"status":"UP"}
sudo -u ubuntu jcmd $(pgrep -f app.jar) VM.flags | tr ' ' '\n' | grep HeapSize
systemctl show wishconnect -p StartLimitBurst -p StartLimitIntervalUSec
free -m
```

## 2026-08-05 적용 이력

출시 전 안정화. 실사용자가 거의 없는데도 OOM 으로 2회 죽어 조치했다.

| 항목 | 변경 |
|---|---|
| 힙 상한 | `-Xms512m -Xmx1g` 명시. 없으면 JVM 이 물리메모리 25% 를 자동 상한으로 잡아 인스턴스 크기에 따라 변한다 |
| 스왑 | 2GB + `vm.swappiness=10` |
| 재시작 폭주 방지 | `StartLimitBurst=5` / `StartLimitIntervalSec=300` |
| 로그 | logrotate 일 단위 7일 보관 (그전까지 app.log 가 무한 증가, 10.7MB) |
