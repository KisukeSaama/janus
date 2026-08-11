/**
 * The examples on the documentation page, built from a real connection when one exists.
 *
 * A guide that prints `<your-application-id>` asks the reader to do the substitution in their head
 * and to hope they guessed the gateway address right. These carry the address this console is served
 * from and the identifier of a connection the reader can see in the rail, so a snippet is copied and
 * run rather than adapted. The key is never one of them: it exists for one moment, on the screen that
 * issued it, and this page is not that screen.
 */

export type SampleContext = {
  /** Where Janus itself answers, taken from the address the console was opened at. */
  origin: string;
  username: string;
  slug: string;
  applicationId: string;
  /** A path the connection actually allows, or a plausible one when nothing is registered yet. */
  path: string;
};

export type Snippet = { file: string; code: string };
export type Sample = { id: string; label: string; snippets: Snippet[] };

export const PLACEHOLDER: SampleContext = {
  origin: 'https://janus.example.com',
  username: 'alice',
  slug: 'payments',
  applicationId: '00000000-0000-0000-0000-000000000000',
  path: '/v1/orders',
};

/** `payments-api-v2` becomes `paymentsApiV2`, which is what a method name can be called. */
function toCamel(slug: string): string {
  const parts = slug.split(/[^a-zA-Z0-9]+/).filter(Boolean);
  if (parts.length === 0) return 'api';
  return parts
    .map((part, index) => (index === 0 ? part.toLowerCase() : part[0].toUpperCase() + part.slice(1).toLowerCase()))
    .join('');
}

export function gatewayUrl({ origin, username, slug }: SampleContext): string {
  return `${origin}/${encodeURIComponent(username)}/gateway/${slug}`;
}

/** The three values a caller holds, in the form every runtime already knows how to read. */
export function environmentSample(ctx: SampleContext): Snippet {
  return {
    file: '.env',
    code: [
      `JANUS_URL=${ctx.origin}`,
      `JANUS_APPLICATION_ID=${ctx.applicationId}`,
      'JANUS_API_KEY=the key issued with the connection',
    ].join('\n'),
  };
}

export function firstRequestSample(ctx: SampleContext): Snippet {
  return {
    file: 'curl',
    code: [
      `curl -i ${gatewayUrl(ctx)}${ctx.path} \\`,
      `  -H "X-Janus-Application-Id: ${ctx.applicationId}" \\`,
      '  -H "X-Janus-Api-Key: $JANUS_API_KEY"',
    ].join('\n'),
  };
}

/**
 * The exchange, in the shape RFC 6749 describes. Written out rather than described because the one
 * thing a reader gets wrong here is which of their two values is the client id and which the secret.
 */
export function tokenSample(ctx: SampleContext): Snippet {
  return {
    file: 'curl',
    code: [
      `curl -X POST ${ctx.origin}/oauth/token \\`,
      '  -d grant_type=client_credentials \\',
      `  -d client_id=${ctx.applicationId} \\`,
      '  -d client_secret=$JANUS_API_KEY',
      '',
      '// Then, on every call, instead of the two headers:',
      `curl ${gatewayUrl(ctx)}${ctx.path} \\`,
      '  -H "Authorization: Bearer $ACCESS_TOKEN"',
    ].join('\n'),
  };
}

export function samples(ctx: SampleContext): Sample[] {
  const base = gatewayUrl(ctx);
  const macro = toCamel(ctx.slug);

  return [
    {
      id: 'curl',
      label: 'cURL',
      snippets: [
        {
          file: 'A read',
          code: [
            `curl ${base}${ctx.path} \\`,
            `  -H "X-Janus-Application-Id: ${ctx.applicationId}" \\`,
            '  -H "X-Janus-Api-Key: $JANUS_API_KEY"',
          ].join('\n'),
        },
        {
          file: 'A write',
          code: [
            `curl -X POST ${base}${ctx.path} \\`,
            `  -H "X-Janus-Application-Id: ${ctx.applicationId}" \\`,
            '  -H "X-Janus-Api-Key: $JANUS_API_KEY" \\',
            '  -H "Content-Type: application/json" \\',
            `  -d '{"reference":"A-1024"}'`,
          ].join('\n'),
        },
      ],
    },
    {
      id: 'php',
      label: 'PHP (Laravel)',
      snippets: [
        {
          file: 'config/services.php',
          code: [
            "'janus' => [",
            "    'url' => env('JANUS_URL').'/${ctx.username}/gateway/" + ctx.slug + "',",
            "    'application' => env('JANUS_APPLICATION_ID'),",
            "    'key' => env('JANUS_API_KEY'),",
            '],',
          ].join('\n'),
        },
        {
          file: 'app/Providers/AppServiceProvider.php',
          code: [
            '// One macro, so no caller assembles the headers itself.',
            `Http::macro('${macro}', fn () => Http::baseUrl(config('services.janus.url'))`,
            '    ->withHeaders([',
            "        'X-Janus-Application-Id' => config('services.janus.application'),",
            "        'X-Janus-Api-Key' => config('services.janus.key'),",
            '    ])',
            '    ->timeout(35));',
          ].join('\n'),
        },
        {
          file: 'app/Services/Orders.php',
          code: [
            `$response = Http::${macro}()->get('${ctx.path}');`,
            '',
            '// A refusal by Janus is problem+json; anything else came from the API itself.',
            "if ($response->header('Content-Type') === 'application/problem+json') {",
            "    report(new RuntimeException($response->json('detail').' '.$response->header('X-Janus-Correlation-Id')));",
            '}',
            '',
            '$orders = $response->throw()->json();',
          ].join('\n'),
        },
      ],
    },
    {
      id: 'js',
      label: 'JavaScript',
      snippets: [
        {
          file: 'janus.js',
          code: [
            `const base = \`\${process.env.JANUS_URL}/${ctx.username}/gateway/${ctx.slug}\`;`,
            'const identity = {',
            "  'X-Janus-Application-Id': process.env.JANUS_APPLICATION_ID,",
            "  'X-Janus-Api-Key': process.env.JANUS_API_KEY,",
            '};',
            '',
            'export async function janus(path, init = {}) {',
            '  const response = await fetch(base + path, {',
            '    ...init,',
            '    headers: { ...identity, ...init.headers },',
            '  });',
            '',
            '  if (!response.ok) {',
            '    // Who refused, before what to do about it.',
            "    const refusedByJanus = (response.headers.get('content-type') ?? '').includes('problem+json');",
            '    throw new Error(',
            "      `${refusedByJanus ? 'Janus refused' : 'API failed'}: ${response.status} ` +",
            "        `(${response.headers.get('X-Janus-Correlation-Id')})`,",
            '    );',
            '  }',
            '',
            '  return response.json();',
            '}',
          ].join('\n'),
        },
        {
          file: 'orders.js',
          code: [`const orders = await janus('${ctx.path}');`].join('\n'),
        },
      ],
    },
    {
      id: 'python',
      label: 'Python',
      snippets: [
        {
          file: 'janus.py',
          code: [
            'import os',
            'import requests',
            '',
            `BASE = f"{os.environ['JANUS_URL']}/${ctx.username}/gateway/${ctx.slug}"`,
            '',
            'session = requests.Session()',
            'session.headers.update({',
            '    "X-Janus-Application-Id": os.environ["JANUS_APPLICATION_ID"],',
            '    "X-Janus-Api-Key": os.environ["JANUS_API_KEY"],',
            '})',
            '',
            '',
            'def call(method, path, **kwargs):',
            '    response = session.request(method, BASE + path, timeout=35, **kwargs)',
            '    if response.status_code >= 400:',
            '        refused_by_janus = "problem+json" in response.headers.get("Content-Type", "")',
            '        raise RuntimeError(',
            '            f\'{"Janus refused" if refused_by_janus else "API failed"}: \'',
            '            f\'{response.status_code} ({response.headers.get("X-Janus-Correlation-Id")})\'',
            '        )',
            '    return response.json()',
          ].join('\n'),
        },
        {
          file: 'orders.py',
          code: `orders = call("GET", "${ctx.path}")`,
        },
      ],
    },
    {
      id: 'java',
      label: 'Java',
      snippets: [
        {
          file: 'JanusClient.java',
          code: [
            'var http = HttpClient.newHttpClient();',
            '',
            'var request = HttpRequest.newBuilder()',
            `        .uri(URI.create(System.getenv("JANUS_URL") + "/${ctx.username}/gateway/${ctx.slug}${ctx.path}"))`,
            '        .header("X-Janus-Application-Id", System.getenv("JANUS_APPLICATION_ID"))',
            '        .header("X-Janus-Api-Key", System.getenv("JANUS_API_KEY"))',
            '        .timeout(Duration.ofSeconds(35))',
            '        .GET()',
            '        .build();',
            '',
            'var response = http.send(request, BodyHandlers.ofString());',
            'if (response.statusCode() >= 400) {',
            '    // problem+json means Janus refused; anything else came from the API.',
            '    boolean refusedByJanus = response.headers()',
            '            .firstValue("content-type")',
            '            .orElse("")',
            '            .contains("problem+json");',
            '    throw new IllegalStateException((refusedByJanus ? "Janus refused: " : "API failed: ")',
            '            + response.statusCode()',
            '            + " " + response.headers().firstValue("X-Janus-Correlation-Id").orElse(""));',
            '}',
          ].join('\n'),
        },
      ],
    },
  ];
}
