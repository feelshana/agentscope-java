# Build context is a generated whitelist of application files, never the repository.
ARG RUNTIME_IMAGE=dataagent/runtime:20261028
FROM ${RUNTIME_IMAGE}
ARG SOURCE_REVISION
LABEL org.opencontainers.image.revision=${SOURCE_REVISION} dataagent.application=true
COPY app/ /opt/dataagent/app/
COPY application-entrypoint.sh /usr/local/bin/dataagent-application
# COPY preserves source modes; archived files may be owner-only under a restrictive umask.
RUN chmod 0755 /usr/local/bin/dataagent-application \
    /opt /opt/dataagent /opt/dataagent/app /opt/dataagent/app/bin /opt/dataagent/app/lib \
    && chmod 0644 /opt/dataagent/app/lib/dataagent-exec.jar \
       /opt/dataagent/app/bin/check-wren.py /opt/dataagent/app/requirements-wren.txt \
       /opt/dataagent/app/source-revision
ENV DATAAGENT_APP_HOME=/opt/dataagent/app
ENTRYPOINT ["/usr/local/bin/dataagent-application"]
