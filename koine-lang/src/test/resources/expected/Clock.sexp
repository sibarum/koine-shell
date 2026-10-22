(module Clock)
  (def sleep (seconds: Number))
    (native Windows)
      | Start-Sleep -Seconds $seconds
    (native Linux Mac)
      | sleep "$seconds"
