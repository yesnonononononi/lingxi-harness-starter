// Framing keeps protocol metadata separate from command bytes and bounds every individual frame.
export function createOutputCollector(request) {
  const chunks = [];
  let size = 0, truncated = false;
  return {
    consume(bytes) {
      const keep = Math.max(0, Math.min(bytes.length, request.maxBytes - size));
      if (keep) {
        const kept = bytes.subarray(0, keep);
        if (request.streamOutput) {
          for (let offset = 0; offset < kept.length; offset += 8192) {
            process.stdout.write(JSON.stringify({type: 'output', data: kept.subarray(offset, offset + 8192).toString('base64')}) + '\n');
          }
        } else chunks.push(Buffer.from(kept));
        size += keep;
      }
      if (keep < bytes.length) truncated = true;
    },
    finish(result) {
      const response = {...result, outputBase64: request.streamOutput ? '' : Buffer.concat(chunks).toString('base64'), truncated};
      process.stdout.write(JSON.stringify(request.streamOutput ? {type: 'result', ...response} : response) + (request.streamOutput ? '\n' : ''));
    }
  };
}
