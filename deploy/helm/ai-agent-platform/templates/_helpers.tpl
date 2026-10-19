{{/*
基础命名与标签。
*/}}
{{- define "ai-agent-platform.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "ai-agent-platform.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- $name := default .Chart.Name .Values.nameOverride }}
{{- if contains $name .Release.Name }}
{{- .Release.Name | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}
{{- end }}

{{- define "ai-agent-platform.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "ai-agent-platform.labels" -}}
helm.sh/chart: {{ include "ai-agent-platform.chart" . }}
{{ include "ai-agent-platform.selectorLabels" . }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- with .Values.commonLabels }}
{{ toYaml . }}
{{- end }}
{{- end }}

{{- define "ai-agent-platform.selectorLabels" -}}
app.kubernetes.io/name: {{ include "ai-agent-platform.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{- define "ai-agent-platform.backend.fullname" -}}
{{- printf "%s-backend" (include "ai-agent-platform.fullname" .) | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "ai-agent-platform.frontend.fullname" -}}
{{- printf "%s-frontend" (include "ai-agent-platform.fullname" .) | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "ai-agent-platform.serviceAccountName" -}}
{{- if .Values.serviceAccount.create }}
{{- default (include "ai-agent-platform.fullname" .) .Values.serviceAccount.name }}
{{- else }}
{{- default "default" .Values.serviceAccount.name }}
{{- end }}
{{- end }}

{{- define "ai-agent-platform.secretName" -}}
{{- if .Values.secrets.existingSecret }}
{{- .Values.secrets.existingSecret }}
{{- else }}
{{- printf "%s-secrets" (include "ai-agent-platform.fullname" .) }}
{{- end }}
{{- end }}
