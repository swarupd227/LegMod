{{/*
Atlas Migrate · helm template helpers.
Standard Bitnami-style helpers — name, labels, image reference.
*/}}

{{/*
Fully-qualified name for a Kubernetes object. Truncated to 63 chars to
satisfy the DNS label limit, with the trailing hyphen stripped if the
truncation creates one.
*/}}
{{- define "atlas.fullname" -}}
{{- $name := default .Chart.Name .Values.nameOverride -}}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/*
Standard Helm labels applied to every object. The `app.kubernetes.io/*`
keys land on selectors so kubectl can scope filters with
`-l app.kubernetes.io/instance=<release>`.
*/}}
{{- define "atlas.labels" -}}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
app.kubernetes.io/name: {{ .Chart.Name }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/part-of: atlas-migrate
{{- end -}}

{{/*
Per-service labels. Adds `atlas.envestnet.com/service` so kubectl can
target one service inside the release.
*/}}
{{- define "atlas.serviceLabels" -}}
{{ include "atlas.labels" .root }}
app.kubernetes.io/component: {{ .name }}
atlas.envestnet.com/service: {{ .name }}
{{- end -}}

{{/*
Image reference. Composes:
  <registry>/<image>:<tag>
where image suffix comes from the per-service `image` value and tag
from the global `imageTag`. Per-service `imageTag` (when set) wins.
*/}}
{{- define "atlas.image" -}}
{{- $registry := .root.Values.global.registry -}}
{{- $tag := default .root.Values.global.imageTag .svc.imageTag -}}
{{- printf "%s/%s:%s" $registry .svc.image $tag -}}
{{- end -}}
