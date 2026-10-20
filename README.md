# Users Management — Spring Boot, Arquitectura Hexagonal y DDD

Aplicación de gestión de usuarios construida con Java 17 y Spring Boot. La API REST es el punto de entrada activo. El servicio expone la API REST y ya no incluye el adaptador de escritorio de la antigua CLI.

Spring es el único *composition root*: `Main` inicia el contexto y las dependencias se resuelven mediante configuración y component scanning de Spring.

## Compilar y verificar

Usar JDK 17, igual que el Dockerfile. En PowerShell, si el PATH selecciona Java 25:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.14.7-hotspot'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\mvnw.cmd clean verify
```

Ajustar JAVA_HOME a la instalacion local de JDK 17. Lombok 1.18.36 no permite compilar este proyecto con Java 25.

La prueba del antiguo controlador de escritorio se conserva en `src/legacy-test/java` como referencia historica. No pertenece a la suite activa porque user-service ya no incluye el adaptador desktop. Las pruebas de la API REST permanecen en `src/test/java`.

## Creacion idempotente de usuarios

`POST /api/users` permite reintentar la creacion con el mismo ID, nombre, correo,
rol y contrasena: devuelve el usuario existente con `200`, sin insertar otra fila
ni publicar otro evento de creacion. El ID debe conservarse entre reintentos; el correo
solo no identifica una repeticion de la misma solicitud. Si el ID o correo ya
esta ocupado por datos diferentes, devuelve `409`. Los duplicados detectados
por TiDB durante solicitudes concurrentes tambien se resuelven con estas reglas.
La comparacion usa los valores normalizados del dominio y verifica la contrasena
contra el hash almacenado; la contrasena no se incluye en la respuesta.

La creacion inicial satisfactoria responde `201`; el cuerpo conserva el mismo formato
en la creacion y en los reintentos exitosos.
