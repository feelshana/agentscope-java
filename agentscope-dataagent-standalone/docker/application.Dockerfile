# Build context is a generated whitelist of application files, never the repository.
ARG RUNTIME_IMAGE=dataagent/runtime:20261028
FROM ${RUNTIME_IMAGE}
ARG SOURCE_REVISION
LABEL org.opencontainers.image.revision=${SOURCE_REVISION} dataagent.application=true
COPY app/ /opt/dataagent/app/
COPY application-entrypoint.sh /usr/local/bin/dataagent-application
RUN chmod 0755 /usr/local/bin/dataagent-application
ENV DATAAGENT_APP_HOME=/opt/dataagent/app
ENTRYPOINT ["/usr/local/bin/dataagent-application"]
