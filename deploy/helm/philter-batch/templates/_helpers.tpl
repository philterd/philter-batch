{{/* The release's full name, used for every object. */}}
{{- define "philter-batch.fullname" -}}
{{- if contains .Chart.Name .Release.Name -}}
{{- .Release.Name | trunc 52 | trimSuffix "-" -}}
{{- else -}}
{{- printf "%s-%s" .Release.Name .Chart.Name | trunc 52 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}

{{- define "philter-batch.selectorLabels" -}}
app.kubernetes.io/name: {{ .Chart.Name }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{- define "philter-batch.labels" -}}
{{ include "philter-batch.selectorLabels" . }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" }}
{{- end -}}

{{- define "philter-batch.serviceAccountName" -}}
{{- default (include "philter-batch.fullname" .) .Values.serviceAccount.name -}}
{{- end -}}

{{/* The philter-batch image pinned in images.yaml, as a dict (source, tag, digest, repository). */}}
{{- define "philter-batch.pinnedImage" -}}
{{- $images := (.Files.Get "images.yaml" | fromYaml).images | default dict -}}
{{- toYaml (index $images "philter-batch" | default dict) -}}
{{- end -}}

{{/* The image's digest: image.digest, or the one pinned in images.yaml. */}}
{{- define "philter-batch.imageDigest" -}}
{{- $pinned := include "philter-batch.pinnedImage" . | fromYaml -}}
{{- $digest := .Values.image.digest | default $pinned.digest -}}
{{- if not $digest -}}
{{- fail "image.digest is required (sha256:...) until images.yaml pins the philter-batch image: the image is pinned by digest, never by tag" -}}
{{- end -}}
{{- if not (regexMatch "^sha256:[a-f0-9]{64}$" $digest) -}}
{{- fail "image.digest must be sha256: followed by 64 lowercase hex characters" -}}
{{- end -}}
{{- $digest -}}
{{- end -}}

{{/* The image by digest; a tag is never used. */}}
{{- define "philter-batch.image" -}}
{{- $pinned := include "philter-batch.pinnedImage" . | fromYaml -}}
{{- $registry := required "image.registry is required: the registry the images were copied to, such as the account's ECR" .Values.image.registry | trimSuffix "/" -}}
{{- $repo := .Values.image.repository | default $pinned.repository -}}
{{- if regexMatch ":[^/]*$" $repo -}}
{{- fail "image.repository must not include a tag; the image is pinned by image.digest" -}}
{{- end -}}
{{- printf "%s/%s@%s" $registry $repo (include "philter-batch.imageDigest" .) -}}
{{- end -}}

{{- define "philter-batch.consoleServiceAccountName" -}}
{{- default (printf "%s-console" (include "philter-batch.fullname" .)) .Values.console.serviceAccount.name -}}
{{- end -}}

{{/* Checks that span several values. Rendered once, from the ConfigMap. */}}
{{- define "philter-batch.validate" -}}
{{- $_ := required "output.bucket is required: the bucket runs write to" .Values.output.bucket -}}
{{- if ne (toString .Values.store.type) "s3" -}}
{{- fail "store.type must be s3 (the only store in this version)" -}}
{{- end -}}
{{- $_ := include "philter-batch.image" . -}}
{{- $cpu := .Values.workers.cpu -}}
{{- $threads := .Values.workers.threads -}}
{{- if or (kindIs "string" $cpu) (ne (toString $cpu) (toString (int $cpu))) (lt (int $cpu) 1) -}}
{{- fail "workers.cpu must be a whole number of CPUs, at least 1, so ONNX Runtime's thread count matches the limit" -}}
{{- end -}}
{{- if or (lt (int $threads) 1) (ne 0 (mod (int $cpu) (int $threads))) -}}
{{- fail "workers.cpu must be a multiple of workers.threads" -}}
{{- end -}}
{{- if lt (int .Values.workers.count) 1 -}}
{{- fail "workers.count must be at least 1" -}}
{{- end -}}
{{- if lt (int .Values.workers.shutdownGraceSeconds) 1 -}}
{{- fail "workers.shutdownGraceSeconds must be at least 1" -}}
{{- end -}}
{{- if le (int .Values.workers.terminationGracePeriodSeconds) (int .Values.workers.shutdownGraceSeconds) -}}
{{- fail "workers.terminationGracePeriodSeconds must be above workers.shutdownGraceSeconds" -}}
{{- end -}}
{{- if lt (int .Values.gates.pollSeconds) 1 -}}
{{- fail "gates.pollSeconds must be at least 1" -}}
{{- end -}}
{{- if .Values.networkPolicy.enabled -}}
{{- if empty .Values.networkPolicy.kubernetesApi.to -}}
{{- fail "networkPolicy.kubernetesApi.to is required when the network policy is enabled (see docs/kubernetes.md)" -}}
{{- end -}}
{{- if empty .Values.networkPolicy.objectStore.to -}}
{{- fail "networkPolicy.objectStore.to is required when the network policy is enabled (see docs/kubernetes.md)" -}}
{{- end -}}
{{- if and .Values.pseudonymization.secretArn (empty .Values.networkPolicy.secretsManager.to) -}}
{{- fail "networkPolicy.secretsManager.to is required when the network policy is enabled and pseudonymization.secretArn is set (see docs/kubernetes.md)" -}}
{{- end -}}
{{- end -}}
{{- with .Values.pseudonymization.secretArn -}}
{{- if not (regexMatch "^arn:aws[a-z-]*:secretsmanager:[a-z]{2}(-[a-z]+)+-[0-9]:[0-9]{12}:secret:[A-Za-z0-9/_+=.@-]+-[A-Za-z0-9]{6}$" .) -}}
{{- fail "pseudonymization.secretArn must be a Secrets Manager secret's full ARN, ending in the six characters AWS adds to its name" -}}
{{- end -}}
{{- end -}}
{{- if and .Values.pseudonymization.endpoint (not .Values.pseudonymization.secretArn) -}}
{{- fail "pseudonymization.endpoint is set without pseudonymization.secretArn" -}}
{{- end -}}
{{- end -}}

{{/* deployment.yaml: where runs keep everything. Every pod reads it. */}}
{{- define "philter-batch.deployment" -}}
{{- $store := dict "type" .Values.store.type "path_style" (.Values.store.path_style | default false) -}}
{{- with .Values.store.region }}{{ $_ := set $store "region" . }}{{ end -}}
{{- with .Values.store.endpoint }}{{ $_ := set $store "endpoint" . }}{{ end -}}
{{- $deployment := dict "store" $store "output" (dict "bucket" .Values.output.bucket "prefix" (.Values.output.prefix | default "")) -}}
{{- with .Values.pseudonymization.secretArn -}}
{{- $p := dict "secret_arn" . -}}
{{- with $.Values.pseudonymization.endpoint }}{{ $_ := set $p "endpoint" . }}{{ end -}}
{{- $_ := set $deployment "pseudonymization" $p -}}
{{- end -}}
{{- toYaml $deployment -}}
{{- end -}}

{{/* The environment every philter-batch container gets. */}}
{{- define "philter-batch.env" -}}
- name: PHILTER_BATCH_DEPLOYMENT
  value: /config/deployment.yaml
- name: DJL_CACHE_DIR
  value: /tmp/djl
- name: PHILTER_BATCH_IMAGE_DIGEST
  value: {{ include "philter-batch.imageDigest" . | quote }}
{{- end -}}

{{/* The container security context of every pod. */}}
{{- define "philter-batch.containerSecurity" -}}
allowPrivilegeEscalation: false
readOnlyRootFilesystem: true
runAsNonRoot: true
capabilities:
  drop: ["ALL"]
{{- end -}}

{{/*
A container running the image with the configuration mounted. Argo's emissary executor needs the command spelled
out (it does not read the image's entrypoint), so it repeats the Dockerfile's ENTRYPOINT.
Arguments: ctx (the root context), args, cpu, memory, javaOptions.
*/}}
{{- define "philter-batch.container" -}}
{{- $ctx := .ctx -}}
image: {{ include "philter-batch.image" $ctx }}
imagePullPolicy: {{ $ctx.Values.image.pullPolicy }}
command: ["java", "--enable-native-access=ALL-UNNAMED", "-jar", "/opt/philter-batch/philter-batch.jar"]
args:
{{ toYaml .args }}
env:
  - name: JAVA_TOOL_OPTIONS
    value: {{ .javaOptions | quote }}
  {{- include "philter-batch.env" $ctx | nindent 2 }}
{{- if $ctx.Values.credentialsSecret }}
envFrom:
  - secretRef:
      name: {{ $ctx.Values.credentialsSecret }}
{{- end }}
resources:
  requests:
    cpu: {{ .cpu | quote }}
    memory: {{ .memory | quote }}
  limits:
    cpu: {{ .cpu | quote }}
    memory: {{ .memory | quote }}
securityContext:
  {{- include "philter-batch.containerSecurity" . | nindent 2 }}
volumeMounts:
  - name: config
    mountPath: /config
    readOnly: true
  - name: tmp
    mountPath: /tmp
{{- end -}}

{{/* The memory-backed /tmp. Kubernetes mounts emptyDir without noexec, which ONNX Runtime needs. */}}
{{- define "philter-batch.tmpVolume" -}}
- name: tmp
  emptyDir:
    medium: Memory
    sizeLimit: {{ . }}
{{- end -}}

{{/* Placement for a template: nodeSelector, tolerations, and affinity from a values block. */}}
{{- define "philter-batch.placement" -}}
{{- with .nodeSelector }}
nodeSelector:
{{ toYaml . | indent 2 }}
{{- end }}
{{- with .tolerations }}
tolerations:
{{ toYaml . | indent 2 }}
{{- end }}
{{- with .affinity }}
affinity:
{{ toYaml . | indent 2 }}
{{- end }}
{{- end -}}

{{/* Retries for check-store, inventory, and finalize: pod errors and kills only, never an exit code of theirs. */}}
{{- define "philter-batch.stepRetry" -}}
limit: {{ .Values.steps.retryLimit | quote }}
retryPolicy: Always
expression: 'lastRetry.status == "Error" || asInt(lastRetry.exitCode) in [137, 143]'
{{- end -}}

{{/* Retries for the sample and gate steps: also exit 3, stopped by SIGTERM with nothing lost. */}}
{{- define "philter-batch.stoppableRetry" -}}
limit: {{ .Values.steps.retryLimit | quote }}
retryPolicy: Always
expression: 'lastRetry.status == "Error" || asInt(lastRetry.exitCode) in [3, 137, 143]'
{{- end -}}

{{/*
A small step running a shell command in the image, for messages in the log. Its values come in through the
environment, never into the command line. Arguments: ctx, script, env (a list of name and value).
*/}}
{{- define "philter-batch.shellStep" -}}
{{- $ctx := .ctx -}}
{{- with include "philter-batch.placement" $ctx.Values.steps | trim }}{{ . }}{{ "\n" }}{{ end -}}
volumes:
  {{- include "philter-batch.tmpVolume" "16Mi" | nindent 2 }}
container:
  image: {{ include "philter-batch.image" $ctx }}
  imagePullPolicy: {{ $ctx.Values.image.pullPolicy }}
  command: ["sh", "-c"]
  args:
    - {{ .script | squote }}
  env:
    {{- toYaml .env | nindent 4 }}
  resources:
    requests:
      cpu: 100m
      memory: 64Mi
    limits:
      cpu: 100m
      memory: 64Mi
  securityContext:
    {{- include "philter-batch.containerSecurity" . | nindent 4 }}
  volumeMounts:
    - name: tmp
      mountPath: /tmp
{{- end -}}
