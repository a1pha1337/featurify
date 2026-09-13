The certificate and private key in this directory are public test fixtures for localhost.
They are used only by FeaturifyGrpcAutoConfigurationTests; never use them for a deployed server.

Regenerate both files together when the certificate expires:

```shell
openssl req -x509 -newkey rsa:2048 -nodes -keyout server.key -out server.crt -days 3650 \
  -subj '/CN=localhost' -addext 'subjectAltName=DNS:localhost,IP:127.0.0.1'
```
