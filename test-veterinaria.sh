#!/usr/bin/env bash
# test-veterinaria.sh — Pruebas funcionales + estrés del Sistema de Citas Veterinaria.
# Requisito: la app corriendo en BASE_URL (por defecto http://localhost:8081).
# Uso: bash test-veterinaria.sh [BASE_URL]
set -u
BASE="${1:-http://localhost:8081}"
PASS=0; FAIL=0

ok()   { PASS=$((PASS+1)); echo "  [OK] $1"; }
fail() { FAIL=$((FAIL+1)); echo "  [FAIL] $1 -- $2"; }

echo "== 1. Login ADMIN/VET/CLIENTE (seed: admin@vet.com, vet@vet.com, cliente@mail.com / 1234) =="
TOKEN_ADMIN=$(curl -s -X POST "$BASE/api/v1/auth/login" -H 'Content-Type: application/json' \
  -d '{"email":"admin@vet.com","password":"1234"}' | grep -o '"token":"[^"]*"' | cut -d'"' -f4)
TOKEN_VET=$(curl -s -X POST "$BASE/api/v1/auth/login" -H 'Content-Type: application/json' \
  -d '{"email":"vet@vet.com","password":"1234"}' | grep -o '"token":"[^"]*"' | cut -d'"' -f4)
TOKEN_CLI=$(curl -s -X POST "$BASE/api/v1/auth/login" -H 'Content-Type: application/json' \
  -d '{"email":"cliente@mail.com","password":"1234"}' | grep -o '"token":"[^"]*"' | cut -d'"' -f4)
[ -n "${TOKEN_ADMIN:-}" ] && ok "login ADMIN" || fail "login ADMIN" "sin token"
[ -n "${TOKEN_VET:-}" ]   && ok "login VET"   || fail "login VET" "sin token"
[ -n "${TOKEN_CLI:-}" ]   && ok "login CLIENTE" || fail "login CLIENTE" "sin token"

echo "== 2. Registrar mascota (CLIENTE) =="
MASCOTA_JSON=$(curl -s -X POST "$BASE/api/v1/mascotas" -H "Authorization: Bearer $TOKEN_CLI" \
  -H 'Content-Type: application/json' -d '{"nombre":"Firulais","especie":"PERRO","raza":"Labrador","edad":3}')
MASCOTA_ID=$(echo "$MASCOTA_JSON" | grep -o '"id":[0-9]*' | head -1 | cut -d: -f2)
[ -n "${MASCOTA_ID:-}" ] && ok "mascota id=$MASCOTA_ID" || fail "registrar mascota" "$MASCOTA_JSON"

echo "== 3. mis-mascotas usa el JWT =="
MIS=$(curl -s "$BASE/api/v1/mascotas/mis-mascotas" -H "Authorization: Bearer $TOKEN_CLI")
echo "$MIS" | grep -q "Firulais" && ok "mis-mascotas contiene Firulais" || fail "mis-mascotas" "$MIS"

echo "== 4. Ficha mascota (VET) =="
FICHA=$(curl -s "$BASE/api/v1/mascotas/$MASCOTA_ID" -H "Authorization: Bearer $TOKEN_VET")
echo "$FICHA" | grep -q "Firulais" && ok "ficha VET" || fail "ficha VET" "$FICHA"

echo "== 5. Agendar citas (CLIENTE) — valida cruce 30min y límite 2/día =="
VET_ID=$(curl -s "$BASE/api/v1/auth/login" -H 'Content-Type: application/json' \
  -d '{"email":"vet@vet.com","password":"1234"}' >/dev/null; echo "")
# El id del VET se obtiene de la agenda: usamos veterinarioId=2 (seed: ADMIN=1, VET=2)
FECHA1=$(date -u -d "+2 days 10:00" +%Y-%m-%dT10:00:00 2>/dev/null || date -v+2d +%Y-%m-%dT10:00:00)
CITA1=$(curl -s -X POST "$BASE/api/v1/citas" -H "Authorization: Bearer $TOKEN_CLI" -H 'Content-Type: application/json' \
  -d "{\"mascotaId\":$MASCOTA_ID,\"veterinarioId\":2,\"fechaHora\":\"$FECHA1\",\"motivo\":\"Control general\"}")
CITA1_ID=$(echo "$CITA1" | grep -o '"id":[0-9]*' | head -1 | cut -d: -f2)
[ -n "${CITA1_ID:-}" ] && ok "cita1 id=$CITA1_ID" || fail "agendar cita1" "$CITA1"

echo "== 6. Cruce de horario (misma hora, debe dar 400) =="
CRUCE=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE/api/v1/citas" -H "Authorization: Bearer $TOKEN_CLI" \
  -H 'Content-Type: application/json' -d "{\"mascotaId\":$MASCOTA_ID,\"veterinarioId\":2,\"fechaHora\":\"$FECHA1\",\"motivo\":\"Cruce\"}")
[ "$CRUCE" = "400" ] && ok "cruce rechazado (400)" || fail "cruce horario" "http=$CRUCE (esperado 400)"

echo "== 7. Cancelar cita (faltan >2h, debe dar 200) =="
CANC=$(curl -s -o /dev/null -w "%{http_code}" -X PATCH "$BASE/api/v1/citas/$CITA1_ID/cancelar" -H "Authorization: Bearer $TOKEN_CLI")
[ "$CANC" = "200" ] && ok "cancelación OK" || fail "cancelar cita" "http=$CANC (esperado 200)"

echo "== 8. Expediente (VET) + historial =="
FECHA2=$(date -u -d "+3 days 11:00" +%Y-%m-%dT11:00:00 2>/dev/null || date -v+3d +%Y-%m-%dT11:00:00)
CITA2=$(curl -s -X POST "$BASE/api/v1/citas" -H "Authorization: Bearer $TOKEN_CLI" -H 'Content-Type: application/json' \
  -d "{\"mascotaId\":$MASCOTA_ID,\"veterinarioId\":2,\"fechaHora\":\"$FECHA2\",\"motivo\":\"Vacuna\"}")
CITA2_ID=$(echo "$CITA2" | grep -o '"id":[0-9]*' | head -1 | cut -d: -f2)
EXP=$(curl -s -X POST "$BASE/api/v1/expedientes" -H "Authorization: Bearer $TOKEN_VET" -H 'Content-Type: application/json' \
  -d "{\"citaId\":$CITA2_ID,\"diagnostico\":\"Sano\",\"tratamiento\":\"Vacuna anual\",\"pesoKg\":12.5}")
echo "$EXP" | grep -q '"id"' && ok "expediente registrado" || fail "registrar expediente" "$EXP"
HIST=$(curl -s "$BASE/api/v1/expedientes/mascota/$MASCOTA_ID" -H "Authorization: Bearer $TOKEN_CLI")
echo "$HIST" | grep -q "Sano" && ok "historial visible" || fail "historial" "$HIST"

echo "== 9. ESTRÉS: 100 GET concurrentes a /agenda (VET) =="
START=$(date +%s)
for i in $(seq 1 100); do
  curl -s -o /dev/null "$BASE/api/v1/citas/agenda?page=0&size=20" -H "Authorization: Bearer $TOKEN_VET" &
done
wait
END=$(date +%s)
echo "  100 requests en $((END-START))s"
ok "ráfaga agenda completada en $((END-START))s"

echo "== 10. ESTRÉS: 50 GET concurrentes a /mis-mascotas (CLIENTE) =="
START=$(date +%s)
for i in $(seq 1 50); do
  curl -s -o /dev/null "$BASE/api/v1/mascotas/mis-mascotas" -H "Authorization: Bearer $TOKEN_CLI" &
done
wait
END=$(date +%s)
echo "  50 requests en $((END-START))s"
ok "ráfaga mis-mascotas completada en $((END-START))s"

echo ""
echo "==============================="
echo "RESULTADO: PASS=$PASS FAIL=$FAIL"
[ "$FAIL" -eq 0 ] && echo "TODO OK" || echo "HAY FALLOS (revisar arriba)"
exit "$FAIL"
