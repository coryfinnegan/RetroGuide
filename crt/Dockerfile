# Retro Guide (CRT) - the page and the little server that hands it out.
#
# Deliberately server only. The browser stays on the machine wired to the
# tube: it is the thing that must reach the display, and on a Pi that means
# talking to KMS or X on the host, which is far more trouble inside a
# container than out. See README.md for the kiosk side.
#
# Pure standard library, so there is nothing to compile and the same
# Dockerfile builds for a Pi as for a PC.
FROM python:3.12-alpine

LABEL org.opencontainers.image.title="Retro Guide (CRT)" \
      org.opencontainers.image.description="VCR-styled ErsatzTV front end for a CRT" \
      org.opencontainers.image.source="https://github.com/coryfinnegan/RetroGuide" \
      org.opencontainers.image.licenses="MIT"

WORKDIR /app
COPY serve.py ./
COPY static ./static

# Discovery sweeps the LAN, which only works on the host network - see README.
EXPOSE 8464

HEALTHCHECK --interval=30s --timeout=5s --start-period=5s \
  CMD wget -q -O /dev/null http://127.0.0.1:8464/ || exit 1

# Unbuffered so docker logs show something as it happens.
ENV PYTHONUNBUFFERED=1
ENTRYPOINT ["python", "serve.py"]
CMD ["--port", "8464"]
