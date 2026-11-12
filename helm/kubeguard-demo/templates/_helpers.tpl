{{- define "kubeguard-demo.labels" -}}
app.kubernetes.io/name: {{ .name }}
app.kubernetes.io/part-of: kubeguard-demo
app.kubernetes.io/managed-by: {{ .root.Release.Service }}
helm.sh/chart: {{ printf "%s-%s" .root.Chart.Name .root.Chart.Version }}
{{- end }}

{{- define "kubeguard-demo.selectorLabels" -}}
app.kubernetes.io/name: {{ .name }}
app.kubernetes.io/part-of: kubeguard-demo
{{- end }}

{{- define "kubeguard-demo.imagePullSecrets" -}}
{{- with .Values.imagePullSecrets }}
imagePullSecrets:
  {{- toYaml . | nindent 2 }}
{{- end }}
{{- end }}

{{/*
A map as the responder reads it, "key=value,..." with keys sorted: responder.autonomy
("restart-pod=auto,rollback=approve") and responder.defaultActions.
*/}}
{{- define "kubeguard-demo.autonomy" -}}
{{- $pairs := list }}
{{- range $action, $mode := . }}
{{- $pairs = append $pairs (printf "%s=%s" $action $mode) }}
{{- end }}
{{- join "," $pairs }}
{{- end }}
