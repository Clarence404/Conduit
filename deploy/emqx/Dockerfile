# 把 Conduit 的 EMQX 配置模板与 ACL 烘焙进镜像，而不是 bind mount：
# 这样 Docker daemon 在远程主机（DOCKER_HOST=tcp://...）时同样可用。
FROM emqx/emqx:5.8.6
COPY --chown=emqx:emqx base.hocon.tpl /conduit/base.hocon.tpl
COPY --chown=emqx:emqx acl.conf /opt/emqx/etc/conduit-acl.conf
