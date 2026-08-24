# UnauthorizedException

Excepción interna utilizada cuando el header `Authorization` está ausente,
malformado o contiene un ID Token Cognito inválido.

`UploadUrlHandler` transforma esta excepción en HTTP `401` sin devolver detalles
criptográficos al navegador.
