#!/usr/bin/env bash
# =====================================================================
# test-api1.sh — Pruebas automatizadas de la API Veterinaria
# (Spring Boot 4.1.1, monolito modular, un solo JAR en puerto 8081)
#
# Uso desde Git Bash (Windows):
#   chmod +x test-api1.sh
#   ./test-api1.sh
#
# Requisitos: API levantada, `jq` instalado, `ab` opcional (solo estrés).
# Solo usa endpoints que REALMENTE existen en el proyecto:
#   POST /api/v1/auth/register | POST /api/v1/auth/login
#   POST /api/v1/mascotas | GET /api/v1/mascotas/mis-mascotas
#   GET  /api/v1/mascotas/{id}
#   POST /api/v1/citas | GET /api/v1/citas/agenda
#   PATCH /api/v1/citas/{id}/cancelar
#   POST /api/v1/expedientes | GET /api/v1/expedientes/mascota/{id}
# =====================================================================

BASE="${BASE:-http://localhost:8081}"
# Id del usuario VET semilla (admin=1, vet=2, cliente=3 en BD vacía).
# Si tu BD ya tenía usuarios, ajusta:  VET_ID=5 ./test-api1.sh
VET_ID="${VET_ID:-2}"

PASS=0
FAIL=0
TMP_BODY="$(mktemp)"
trap 'rm -f "$TMP_BODY" "$CODES_FILE" 2>/dev/null' EXIT

# ---------- utilidades ----------
ok()   { PASS=$((PASS + 1)); echo "[OK]   $1"; }
fail() { FAIL=$((FAIL + 1)); echo "[FAIL] $1 (esperado: $2, obtenido: $3)"; }
check() { # check "nombre" ESPERADO OBTENIDO
  if [ "$2" = "$3" ]; then ok "$1"; else fail "$1" "$2" "$3"; fi
}
seccion() {
  echo ""
  echo "========================================"
  echo "$1"
  echo "========================================"
}
# req METODO URL TOKEN [ARCHIVO_JSON] -> deja HTTP_CODE y cuerpo en $TMP_BODY
req() {
  local m="$1" u="$2" t="$3" d="${4:-}"
  local args=(-s -o "$TMP_BODY" -w "%{http_code}")
  [ -n "$t" ] && args+=(-H "Authorization: Bearer $t")
  if [ "$m" != "GET" ]; then args+=(-H "Content-Type: application/json"); fi
  [ -n "$d" ] && args+=(-d "@$d")
  HTTP_CODE="$(curl "${args[@]}" -X "$m" "$u")"
}
json() { printf '%s' "$1" > "$TMP_BODY.json"; echo "$TMP_BODY.json"; }

# ---------- requisitos previos ----------
if ! command -v jq >/dev/null 2>&1; then
  echo "[FAIL] 'jq' no está instalado. En PowerShell:  winget install jqlang.jq"
  exit 1
fi
if ! curl -s -o /dev/null --max-time 5 "$BASE/actuator/health"; then
  echo "[FAIL] La API no responde en $BASE. Levántala primero (Run VeterinariaApplication)."
  exit 1
fi
echo "API OK en $BASE"

TS="$(date +%s)"
EMAIL_CLI="testcli${TS}@mail.com"
EMAIL_CLI2="testcli2_${TS}@mail.com"
DIA3="$(date -d "+3 days" "+%Y-%m-%d")"
DIA6="$(date -d "+6 days" "+%Y-%m-%d")"
CITA1="${DIA3}T10:00:00"
CITA_SOLAPE="${DIA3}T10:15:00"   # solapa con CITA1 (|diff| < 30 min) -> 409
CITA2="${DIA3}T11:00:00"
CITA3="${DIA3}T12:00:00"         # tercera del día -> 409 (límite 2 PENDIENTES)
CITA_SOON="$(date -d "+30 minutes" "+%Y-%m-%dT%H:%M:00")"  # cancelar -> 409 (< 2h)
CITA_CONC="${DIA6}T14:00:00"     # slot exclusivo de la prueba de concurrencia

# =====================================================================
seccion "PRUEBAS DE AUTENTICACIÓN"
# =====================================================================
req POST "$BASE/api/v1/auth/register" "" "$(json "{\"nombre\":\"Test\",\"telefono\":\"555\",\"email\":\"$EMAIL_CLI\",\"password\":\"1234\"}")"
check "Registro público (201 + JWT)" "201" "$HTTP_CODE"
TOKEN_REG="$(jq -r '.token // empty' "$TMP_BODY")"
[ -n "$TOKEN_REG" ] && ok "JWT obtenido en registro" || fail "JWT obtenido en registro" "token no vacío" "vacío"

req POST "$BASE/api/v1/auth/login" "" "$(json '{"email":"admin@vet.com","password":"1234"}')"
check "Login ADMIN (200)" "200" "$HTTP_CODE"
TOKEN_ADMIN="$(jq -r '.token // empty' "$TMP_BODY")"

req POST "$BASE/api/v1/auth/login" "" "$(json '{"email":"vet@vet.com","password":"1234"}')"
check "Login VET (200)" "200" "$HTTP_CODE"
TOKEN_VET="$(jq -r '.token // empty' "$TMP_BODY")"

req POST "$BASE/api/v1/auth/login" "" "$(json '{"email":"cliente@mail.com","password":"1234"}')"
check "Login CLIENTE (200)" "200" "$HTTP_CODE"
TOKEN_CLI="$(jq -r '.token // empty' "$TMP_BODY")"

req POST "$BASE/api/v1/auth/login" "" "$(json '{"email":"cliente@mail.com","password":"xxxx"}')"
check "Login con clave mala (401)" "401" "$HTTP_CODE"

req POST "$BASE/api/v1/auth/register" "" "$(json "{\"nombre\":\"Dup\",\"email\":\"$EMAIL_CLI\",\"password\":\"1234\"}")"
check "Registro con email duplicado (409)" "409" "$HTTP_CODE"

# =====================================================================
seccion "PRUEBAS DE AUTORIZACIÓN"
# =====================================================================
req GET "$BASE/api/v1/mascotas/mis-mascotas" ""
check "Sin JWT → 401" "401" "$HTTP_CODE"

req GET "$BASE/api/v1/mascotas/mis-mascotas" "TOKEN_FALSO_123"
check "Token inválido → 401" "401" "$HTTP_CODE"

req GET "$BASE/api/v1/citas/agenda" "$TOKEN_CLI"
check "CLIENTE en endpoint de VET/ADMIN → 403 (no 500)" "403" "$HTTP_CODE"
if grep -q "Internal Server Error\|\"status\":500" "$TMP_BODY"; then
  fail "AuthorizationDenied NO termina en 500" "sin status 500" "contiene 500"
else
  ok "AuthorizationDenied NO termina en 500"
fi

req POST "$BASE/api/v1/citas" "$TOKEN_VET" "$(json '{"mascotaId":1,"veterinarioId":1,"fechaHora":"2030-01-01T10:00:00"}')"
check "VET agendando cita (solo CLIENTE/ADMIN) → 403" "403" "$HTTP_CODE"

req GET "$BASE/api/v1/mascotas/mis-mascotas?page=0&size=5" "$TOKEN_CLI"
check "CLIENTE con JWT válido (200)" "200" "$HTTP_CODE"

# =====================================================================
seccion "PRUEBAS DE MASCOTAS"
# =====================================================================
req POST "$BASE/api/v1/mascotas" "$TOKEN_CLI" "$(json '{"nombre":"Firulais","especie":"PERRO","raza":"Labrador","edad":3}')"
check "Crear mascota (201)" "201" "$HTTP_CODE"
MASCOTA_ID="$(jq -r '.id // empty' "$TMP_BODY")"

req GET "$BASE/api/v1/mascotas/$MASCOTA_ID" "$TOKEN_VET"
check "VET consulta ficha (200)" "200" "$HTTP_CODE"

req GET "$BASE/api/v1/mascotas/$MASCOTA_ID" "$TOKEN_CLI"
check "CLIENTE en ficha (solo VET/ADMIN) → 403" "403" "$HTTP_CODE"

req GET "$BASE/api/v1/mascotas/999999" "$TOKEN_VET"
check "Mascota inexistente (404)" "404" "$HTTP_CODE"

req POST "$BASE/api/v1/mascotas" "$TOKEN_CLI" "$(json '{"especie":"GATO"}')"
check "Mascota sin nombre (400 validación)" "400" "$HTTP_CODE"

# Segundo cliente para probar propiedad cruzada
req POST "$BASE/api/v1/auth/register" "" "$(json "{\"nombre\":\"Otro\",\"email\":\"$EMAIL_CLI2\",\"password\":\"1234\"}")"
TOKEN_CLI2="$(jq -r '.token // empty' "$TMP_BODY")"
req GET "$BASE/api/v1/expedientes/mascota/$MASCOTA_ID" "$TOKEN_CLI2"
check "CLIENTE ve historial de mascota AJENA → 403" "403" "$HTTP_CODE"

# =====================================================================
seccion "PRUEBAS DE CITAS"
# =====================================================================
req POST "$BASE/api/v1/citas" "$TOKEN_CLI" "$(json "{\"mascotaId\":$MASCOTA_ID,\"veterinarioId\":$VET_ID,\"fechaHora\":\"$CITA1\",\"motivo\":\"Control\"}")"
check "Crear cita (201)" "201" "$HTTP_CODE"
CITA_ID="$(jq -r '.id // empty' "$TMP_BODY")"

req POST "$BASE/api/v1/citas" "$TOKEN_CLI" "$(json "{\"mascotaId\":$MASCOTA_ID,\"veterinarioId\":$VET_ID,\"fechaHora\":\"$CITA_SOLAPE\",\"motivo\":\"Solape\"}")"
check "Cita solapada 10:15 vs 10:00 (409)" "409" "$HTTP_CODE"

req POST "$BASE/api/v1/citas" "$TOKEN_CLI" "$(json "{\"mascotaId\":$MASCOTA_ID,\"veterinarioId\":$VET_ID,\"fechaHora\":\"$CITA2\",\"motivo\":\"Segunda\"}")"
check "Segunda cita del día (201)" "201" "$HTTP_CODE"
CITA_ID2="$(jq -r '.id // empty' "$TMP_BODY")"

req POST "$BASE/api/v1/citas" "$TOKEN_CLI" "$(json "{\"mascotaId\":$MASCOTA_ID,\"veterinarioId\":$VET_ID,\"fechaHora\":\"$CITA3\",\"motivo\":\"Tercera\"}")"
check "Tercera cita mismo día/cliente (409 límite diario)" "409" "$HTTP_CODE"

req POST "$BASE/api/v1/citas" "$TOKEN_CLI" "$(json "{\"mascotaId\":$MASCOTA_ID,\"veterinarioId\":$VET_ID,\"fechaHora\":\"$CITA_SOON\",\"motivo\":\"Pronto\"}")"
CITA_SOON_ID="$(jq -r '.id // empty' "$TMP_BODY")"
req PATCH "$BASE/api/v1/citas/$CITA_SOON_ID/cancelar" "$TOKEN_CLI"
check "Cancelar con < 2h de anticipación (409)" "409" "$HTTP_CODE"

req PATCH "$BASE/api/v1/citas/$CITA_ID/cancelar" "$TOKEN_CLI"
check "Cancelar con > 2h (200)" "200" "$HTTP_CODE"

req POST "$BASE/api/v1/expedientes" "$TOKEN_VET" "$(json "{\"citaId\":$CITA_ID,\"diagnostico\":\"X\",\"tratamiento\":\"Y\"}")"
check "Expediente de cita CANCELADA (409)" "409" "$HTTP_CODE"

req POST "$BASE/api/v1/expedientes" "$TOKEN_VET" "$(json "{\"citaId\":$CITA_ID2,\"diagnostico\":\"Sano\",\"tratamiento\":\"Ninguno\",\"pesoKg\":12.5}")"
check "Registrar expediente (201, cita→COMPLETADA)" "201" "$HTTP_CODE"
EXP_ID="$(jq -r '.id // empty' "$TMP_BODY")"

req POST "$BASE/api/v1/expedientes" "$TOKEN_VET" "$(json "{\"citaId\":$CITA_ID2,\"diagnostico\":\"Otro\",\"tratamiento\":\"Otro\"}")"
check "Segundo expediente misma cita (409)" "409" "$HTTP_CODE"

req GET "$BASE/api/v1/expedientes/mascota/$MASCOTA_ID" "$TOKEN_CLI"
check "Historial de mascota propia (200)" "200" "$HTTP_CODE"

req GET "$BASE/api/v1/citas/agenda?veterinarioId=$VET_ID&desde=${DIA3}T00:00:00&hasta=${DIA3}T23:59:59&page=0&size=20" "$TOKEN_VET"
check "Agenda del VET (200)" "200" "$HTTP_CODE"

# =====================================================================
seccion "PRUEBA DE CONCURRENCIA (misma reserva x10 en paralelo)"
# =====================================================================
# 10 peticiones SIMULTÁNEAS por el mismo slot libre: exactamente UNA debe
# crear (201) y el resto ser rechazadas (409). Luego se verifica en la agenda
# que solo existe UNA cita en ese horario (sin duplicados).
echo "Slot bajo prueba: vet=$VET_ID fecha=$CITA_CONC"
CODES_FILE="$(mktemp)"
PAYLOAD_CONC="{\"mascotaId\":$MASCOTA_ID,\"veterinarioId\":$VET_ID,\"fechaHora\":\"$CITA_CONC\",\"motivo\":\"Conc\"}"
for i in $(seq 1 10); do
  (
    curl -s -o /dev/null -w "%{http_code}\n" -X POST "$BASE/api/v1/citas" \
      -H "Authorization: Bearer $TOKEN_CLI" -H "Content-Type: application/json" \
      -d "$PAYLOAD_CONC" >> "$CODES_FILE"
  ) &
done
wait
echo "--- códigos obtenidos (agrupados) ---"
sort "$CODES_FILE" | uniq -c
N201="$(grep -c "^201$" "$CODES_FILE" || true)"
N409="$(grep -c "^409$" "$CODES_FILE" || true)"
check "Exactamente 1 reserva creada de 10 intentos" "1" "$N201"
check "Las otras 9 rechazadas por conflicto" "9" "$N409"
req GET "$BASE/api/v1/citas/agenda?veterinarioId=$VET_ID&desde=${DIA6}T00:00:00&hasta=${DIA6}T23:59:59&page=0&size=50" "$TOKEN_VET"
N_EN_AGENDA="$(jq --arg f "$CITA_CONC" '[.content[] | select(.fechaHora == $f)] | length' "$TMP_BODY")"
check "Solo 1 cita en agenda para ese horario" "1" "$N_EN_AGENDA"

# =====================================================================
seccion "PRUEBAS DE ACTUALIZAR Y ELIMINAR"
# =====================================================================
DIA7="$(date -d "+7 days" "+%Y-%m-%d")"

# --- mascotas ---
req PUT "$BASE/api/v1/mascotas/$MASCOTA_ID" "$TOKEN_CLI" "$(json '{"nombre":"Firulais Jr","especie":"PERRO","raza":"Labrador","edad":4}')"
check "CLIENTE actualiza su mascota (200)" "200" "$HTTP_CODE"

req PUT "$BASE/api/v1/mascotas/$MASCOTA_ID" "$TOKEN_CLI2" "$(json '{"nombre":"Hack","especie":"GATO"}')"
check "Otro CLIENTE actualiza mascota ajena (403)" "403" "$HTTP_CODE"

req PUT "$BASE/api/v1/mascotas/$MASCOTA_ID" "$TOKEN_ADMIN" "$(json '{"nombre":"Firulais Adm","especie":"PERRO","raza":"Labrador","edad":5}')"
check "ADMIN actualiza cualquier mascota (200)" "200" "$HTTP_CODE"

req DELETE "$BASE/api/v1/mascotas/$MASCOTA_ID" "$TOKEN_CLI"
check "Borrar mascota CON citas (409)" "409" "$HTTP_CODE"

req POST "$BASE/api/v1/mascotas" "$TOKEN_CLI" "$(json '{"nombre":"Temporal","especie":"AVE"}')"
MASCOTA_TMP="$(jq -r '.id // empty' "$TMP_BODY")"
req DELETE "$BASE/api/v1/mascotas/$MASCOTA_TMP" "$TOKEN_CLI"
check "Borrar mascota SIN citas (204)" "204" "$HTTP_CODE"
req GET "$BASE/api/v1/mascotas/$MASCOTA_TMP" "$TOKEN_VET"
check "Mascota borrada ya no existe (404)" "404" "$HTTP_CODE"

# --- citas ---
req POST "$BASE/api/v1/citas" "$TOKEN_CLI" "$(json "{\"mascotaId\":$MASCOTA_ID,\"veterinarioId\":$VET_ID,\"fechaHora\":\"${DIA7}T10:00:00\",\"motivo\":\"Reprog\"}")"
CITA_UPD="$(jq -r '.id // empty' "$TMP_BODY")"
req PUT "$BASE/api/v1/citas/$CITA_UPD" "$TOKEN_CLI" "$(json "{\"veterinarioId\":$VET_ID,\"fechaHora\":\"${DIA7}T15:00:00\",\"motivo\":\"Reprogramada\"}")"
check "CLIENTE reprograma su cita (200)" "200" "$HTTP_CODE"

req PUT "$BASE/api/v1/citas/$CITA_UPD" "$TOKEN_CLI" "$(json "{\"veterinarioId\":$VET_ID,\"fechaHora\":\"$CITA_CONC\",\"motivo\":\"Choque\"}")"
check "Reprogramar a horario ocupado (409)" "409" "$HTTP_CODE"

req PUT "$BASE/api/v1/citas/$CITA_UPD" "$TOKEN_CLI2" "$(json "{\"veterinarioId\":$VET_ID,\"fechaHora\":\"${DIA7}T16:00:00\"}")"
check "Otro CLIENTE reprograma cita ajena (403)" "403" "$HTTP_CODE"

req DELETE "$BASE/api/v1/citas/$CITA_UPD" "$TOKEN_CLI"
check "Borrar cita PENDIENTE (409, debe cancelarse)" "409" "$HTTP_CODE"
req PATCH "$BASE/api/v1/citas/$CITA_UPD/cancelar" "$TOKEN_CLI"
req DELETE "$BASE/api/v1/citas/$CITA_UPD" "$TOKEN_CLI"
check "Borrar cita CANCELADA (204)" "204" "$HTTP_CODE"
req DELETE "$BASE/api/v1/citas/$CITA_UPD" "$TOKEN_CLI"
check "Borrar cita ya borrada (404)" "404" "$HTTP_CODE"

# --- expedientes ---
req PUT "$BASE/api/v1/expedientes/$EXP_ID" "$TOKEN_VET" "$(json '{"diagnostico":"Sano (corr.)","tratamiento":"Ninguno","pesoKg":13.0}')"
check "VET actualiza expediente (200)" "200" "$HTTP_CODE"

req PUT "$BASE/api/v1/expedientes/$EXP_ID" "$TOKEN_CLI" "$(json '{"diagnostico":"X","tratamiento":"Y"}')"
check "CLIENTE actualiza expediente (403)" "403" "$HTTP_CODE"

req DELETE "$BASE/api/v1/expedientes/$EXP_ID" "$TOKEN_VET"
check "VET borra expediente (solo ADMIN, 403)" "403" "$HTTP_CODE"

req DELETE "$BASE/api/v1/expedientes/$EXP_ID" "$TOKEN_ADMIN"
check "ADMIN borra expediente (204)" "204" "$HTTP_CODE"

req GET "$BASE/api/v1/expedientes/mascota/$MASCOTA_ID" "$TOKEN_CLI"
N_HIST="$(jq 'length' "$TMP_BODY")"
check "Historial vacío tras borrar (0)" "0" "$N_HIST"

# =====================================================================
seccion "PRUEBA DE ESTRÉS (endpoint de LECTURA: agenda paginada)"
# =====================================================================
# Endpoint elegido: GET /api/v1/citas/agenda (VET). Es de solo lectura
# (no crea ni destruye datos), está paginado (size≤100) y es representativo
# del uso real. Alternativa igualmente válida: GET /mis-mascotas.
STRESS_URL="$BASE/api/v1/citas/agenda?veterinarioId=$VET_ID&desde=${DIA3}T00:00:00&hasta=${DIA6}T23:59:59&page=0&size=20"
if command -v ab >/dev/null 2>&1; then
  echo "ApacheBench disponible → 500 peticiones, 50 concurrentes"
  ab -n 500 -c 50 -H "Authorization: Bearer $TOKEN_VET" "$STRESS_URL" 2>&1 \
    | grep -E "Requests per second|Time per request|Failed requests|Complete requests"
else
  echo "'ab' no disponible → fallback curl+xargs: 100 peticiones, 10 en paralelo"
  seq 1 100 | xargs -P10 -I{} curl -s -o /dev/null -w "%{http_code}\n" \
    -H "Authorization: Bearer $TOKEN_VET" "$STRESS_URL" > "$CODES_FILE.stress"
  echo "--- códigos obtenidos (agrupados) ---"
  sort "$CODES_FILE.stress" | uniq -c
  rm -f "$CODES_FILE.stress"
  echo "(Busca códigos distintos de 200: 401/403/429/500 indicarían un problema)"
fi

# =====================================================================
echo ""
echo "========================================"
echo "RESUMEN: $PASS OK, $FAIL FALLIDAS"
echo "========================================"
[ "$FAIL" -eq 0 ]
