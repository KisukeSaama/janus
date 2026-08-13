import type { AuthType, SignatureFields, TokenClientAuth } from '../../api';

/**
 * The APIs a developer is likely to reach for, with their contract already filled in.
 *
 * Registering an API is a form with a dozen fields, and eleven of them have exactly one correct
 * answer that is written down in somebody's documentation. Asking a reader to go and find it is
 * asking them to leave, and to come back with a token endpoint pasted from a blog post. So the
 * answers live here: choosing "Spotify" fills in the destination, the strategy, both endpoints and a
 * sensible scope, and leaves the one thing Janus cannot know — the reader's own client id and secret.
 *
 * These are a starting point, not a source of truth. A provider may move an endpoint the week after
 * this file is written, so every field a preset fills in stays editable, and nothing here is used at
 * runtime: a preset is a set of keystrokes the reader did not have to type.
 *
 * Where an API needs something Janus does not model — a second mandatory header, a version string —
 * the preset says so in `caveat` rather than pretending to be complete.
 */
export type ApiPreset = {
  id: string;
  /** What it is called. Shown as the row's title. */
  name: string;
  /**
   * Which of an API's several contracts this is. Spotify has two: one for the catalogue, one for a
   * person's own library, and they are not interchangeable.
   */
  variant?: string;
  /** One line on what this reaches, so a reader recognises their case rather than the brand. */
  summary: string;
  /** Extra words that should match a search: former names, what people call it, the domain. */
  keywords: string[];
  slug: string;
  baseUrl: string;
  authType: AuthType;
  headerName?: string;
  queryParameter?: string;
  tokenUrl?: string;
  authorizationUrl?: string;
  tokenScopes?: string;
  tokenClientAuth?: TokenClientAuth;
  /** Where the reader gets their credentials, so the next step is a link rather than a search. */
  credentialsUrl?: string;
  /** Something this API needs that Janus does not do for it. Shown as a warning, not hidden. */
  caveat?: string;
} & SignatureFields;

/**
 * Ordered by how often they are reached for rather than alphabetically: the list is read top to
 * bottom by somebody who has not typed anything into the search box yet.
 */
export const API_PRESETS: ApiPreset[] = [
  {
    id: 'spotify-catalog',
    name: 'Spotify',
    variant: 'Catalogue',
    summary: 'Recherche, albums, artistes, podcasts. Tout ce que Spotify publie pour tout le monde.',
    keywords: ['musique', 'music', 'albums', 'artistes', 'search'],
    slug: 'spotify',
    baseUrl: 'https://api.spotify.com/v1',
    authType: 'OAUTH2_CLIENT_CREDENTIALS',
    tokenUrl: 'https://accounts.spotify.com/api/token',
    credentialsUrl: 'https://developer.spotify.com/dashboard',
  },
  {
    id: 'spotify-user',
    name: 'Spotify',
    variant: 'Compte utilisateur',
    summary: 'Playlists, bibliothèque, lecture en cours. Demande l’accord de la personne concernée.',
    keywords: ['musique', 'music', 'playlist', 'bibliothèque', 'library', 'lecture'],
    slug: 'spotify-compte',
    baseUrl: 'https://api.spotify.com/v1',
    authType: 'OAUTH2_AUTHORIZATION_CODE',
    tokenUrl: 'https://accounts.spotify.com/api/token',
    authorizationUrl: 'https://accounts.spotify.com/authorize',
    tokenScopes: 'user-read-private playlist-read-private user-library-read',
    credentialsUrl: 'https://developer.spotify.com/dashboard',
  },
  {
    id: 'pokeapi',
    name: 'PokéAPI',
    summary: 'Pokémon, objets, régions. Aucune clé, aucun compte, aucune limite déclarée.',
    keywords: ['pokemon', 'jeu', 'game', 'ouvert', 'public'],
    slug: 'pokeapi',
    baseUrl: 'https://pokeapi.co/api/v2',
    authType: 'NONE',
  },
  {
    id: 'discord',
    name: 'Discord',
    variant: 'Compte utilisateur',
    summary: 'Profil, serveurs et connexions d’une personne, avec son accord.',
    keywords: ['chat', 'serveur', 'guild', 'oauth'],
    slug: 'discord',
    baseUrl: 'https://discord.com/api/v10',
    authType: 'OAUTH2_AUTHORIZATION_CODE',
    tokenUrl: 'https://discord.com/api/oauth2/token',
    authorizationUrl: 'https://discord.com/oauth2/authorize',
    tokenScopes: 'identify guilds',
    credentialsUrl: 'https://discord.com/developers/applications',
  },
  {
    id: 'discord-bot',
    name: 'Discord',
    variant: 'Bot',
    summary: 'Le jeton d’un bot, qui agit de lui-même et n’a besoin de l’accord de personne.',
    keywords: ['chat', 'bot', 'serveur', 'guild'],
    slug: 'discord-bot',
    baseUrl: 'https://discord.com/api/v10',
    authType: 'API_KEY_HEADER',
    headerName: 'Authorization',
    credentialsUrl: 'https://discord.com/developers/applications',
    caveat: 'La valeur du secret doit commencer par « Bot », espace compris : Bot MTIx…',
  },
  {
    id: 'twitch',
    name: 'Twitch',
    summary: 'Streams, jeux, chaînes. L’application parle en son nom.',
    keywords: ['stream', 'live', 'helix', 'jeu'],
    slug: 'twitch',
    baseUrl: 'https://api.twitch.tv/helix',
    authType: 'OAUTH2_CLIENT_CREDENTIALS',
    tokenUrl: 'https://id.twitch.tv/oauth2/token',
    tokenClientAuth: 'POST',
    credentialsUrl: 'https://dev.twitch.tv/console/apps',
    caveat:
      'Twitch exige aussi un en-tête Client-Id sur chaque requête, que Janus n’ajoute pas : les applications appelantes doivent le transmettre elles-mêmes.',
  },
  {
    id: 'github',
    name: 'GitHub',
    summary: 'Dépôts, issues, actions. Un jeton personnel suffit pour commencer.',
    keywords: ['git', 'repo', 'issues', 'pat', 'token'],
    slug: 'github',
    baseUrl: 'https://api.github.com',
    authType: 'BEARER',
    credentialsUrl: 'https://github.com/settings/tokens',
  },
  // L'INSEE a changé de portail, et les deux façons de s'authentifier coexistent selon l'ancienneté
  // du compte. Plutôt qu'un préréglage qui devine, deux qui disent lequel est lequel : le nom de
  // l'en-tête exact figure sur la fiche de l'application dans le portail.
  {
    id: 'insee-sirene-key',
    name: 'INSEE Sirene',
    variant: 'Clé d’API',
    summary: 'Entreprises françaises : SIREN, SIRET, adresses, activités. Pour le portail actuel.',
    keywords: ['siren', 'siret', 'entreprise', 'france', 'insee', 'etablissement'],
    slug: 'insee-sirene',
    baseUrl: 'https://api.insee.fr/api-sirene/3.11',
    authType: 'API_KEY_HEADER',
    headerName: 'X-INSEE-Api-Key-Integration',
    credentialsUrl: 'https://portail-api.insee.fr/',
    caveat:
      'Vérifiez le nom exact de l’en-tête sur la fiche de votre application dans le portail INSEE : il a changé avec la refonte et n’est pas le même pour tous les comptes.',
  },
  {
    id: 'insee-sirene-oauth',
    name: 'INSEE Sirene',
    variant: 'OAuth2 (ancien portail)',
    summary: 'La même API pour les comptes créés avant la refonte, avec consumer key et secret.',
    keywords: ['siren', 'siret', 'entreprise', 'france', 'insee', 'token'],
    slug: 'insee-sirene-oauth',
    baseUrl: 'https://api.insee.fr/entreprises/sirene/V3.11',
    authType: 'OAUTH2_CLIENT_CREDENTIALS',
    tokenUrl: 'https://api.insee.fr/token',
    credentialsUrl: 'https://portail-api.insee.fr/',
    caveat:
      'À n’utiliser que si votre compte date d’avant la refonte du portail. Le secret est le couple consumer key:consumer secret.',
  },
  {
    id: 'api-adresse',
    name: 'API Adresse',
    variant: 'data.gouv.fr',
    summary: 'Géocodage d’adresses françaises. Ouverte, sans clé.',
    keywords: ['adresse', 'ban', 'géocodage', 'geocoding', 'france', 'datagouv'],
    slug: 'api-adresse',
    baseUrl: 'https://api-adresse.data.gouv.fr',
    authType: 'NONE',
  },
  {
    id: 'openweather',
    name: 'OpenWeather',
    summary: 'Météo courante et prévisions. La clé voyage dans l’adresse.',
    keywords: ['météo', 'weather', 'prévisions', 'forecast'],
    slug: 'openweather',
    baseUrl: 'https://api.openweathermap.org/data/2.5',
    authType: 'API_KEY_QUERY',
    queryParameter: 'appid',
    credentialsUrl: 'https://home.openweathermap.org/api_keys',
  },
  {
    id: 'nasa',
    name: 'NASA',
    summary: 'Image du jour, imagerie Mars, objets géocroiseurs.',
    keywords: ['espace', 'space', 'apod', 'mars'],
    slug: 'nasa',
    baseUrl: 'https://api.nasa.gov',
    authType: 'API_KEY_QUERY',
    queryParameter: 'api_key',
    credentialsUrl: 'https://api.nasa.gov/',
  },
  {
    id: 'stripe',
    name: 'Stripe',
    summary: 'Paiements, clients, abonnements. La clé secrète voyage en Bearer.',
    keywords: ['paiement', 'payment', 'facturation', 'billing'],
    slug: 'stripe',
    baseUrl: 'https://api.stripe.com/v1',
    authType: 'BEARER',
    credentialsUrl: 'https://dashboard.stripe.com/apikeys',
  },
  {
    id: 'notion',
    name: 'Notion',
    summary: 'Pages, bases de données, blocs d’un espace de travail.',
    keywords: ['notes', 'wiki', 'database', 'workspace'],
    slug: 'notion',
    baseUrl: 'https://api.notion.com/v1',
    authType: 'BEARER',
    credentialsUrl: 'https://www.notion.so/my-integrations',
    caveat:
      'Notion exige aussi un en-tête Notion-Version sur chaque requête, que les applications appelantes doivent transmettre.',
  },
  {
    id: 'reddit',
    name: 'Reddit',
    summary: 'Listings, subreddits, commentaires.',
    keywords: ['forum', 'subreddit', 'social'],
    slug: 'reddit',
    baseUrl: 'https://oauth.reddit.com',
    authType: 'OAUTH2_CLIENT_CREDENTIALS',
    tokenUrl: 'https://www.reddit.com/api/v1/access_token',
    credentialsUrl: 'https://www.reddit.com/prefs/apps',
    caveat: 'Reddit refuse les requêtes sans en-tête User-Agent descriptif.',
  },
  {
    id: 'binance',
    name: 'Binance',
    summary: 'Comptes et ordres. Chaque requête est signée plutôt que porteuse de la clé.',
    keywords: ['crypto', 'trading', 'bourse', 'exchange', 'hmac'],
    slug: 'binance',
    baseUrl: 'https://api.binance.com',
    authType: 'HMAC_SIGNATURE',
    headerName: 'X-MBX-APIKEY',
    signatureAlgorithm: 'HMAC_SHA256',
    signatureTemplate: '{query}',
    signatureEncoding: 'HEX',
    signatureParameter: 'signature',
    timestampParameter: 'timestamp',
    credentialsUrl: 'https://www.binance.com/en/my/settings/api-management',
    caveat:
      'Binance compte le temps en millisecondes : la recette utilise {query}, et le paramètre timestamp y est ajouté avant la signature.',
  },
  {
    id: 'coinbase',
    name: 'Coinbase',
    summary: 'Comptes et transactions. Signature sur la méthode, le chemin et le corps.',
    keywords: ['crypto', 'trading', 'exchange', 'hmac'],
    slug: 'coinbase',
    baseUrl: 'https://api.coinbase.com',
    authType: 'HMAC_SIGNATURE',
    headerName: 'CB-ACCESS-KEY',
    signatureAlgorithm: 'HMAC_SHA256',
    signatureTemplate: '{timestamp}{method}{path}{body}',
    signatureEncoding: 'HEX',
    signatureHeader: 'CB-ACCESS-SIGN',
    timestampHeader: 'CB-ACCESS-TIMESTAMP',
    credentialsUrl: 'https://www.coinbase.com/settings/api',
  },
];

/**
 * Presets matching what has been typed, by name, variant, summary or keyword.
 *
 * Deliberately forgiving about accents and case: somebody looking for Pokémon types "pokemon", and
 * finding nothing would teach them the catalogue is empty rather than that their keyboard is.
 */
export function searchPresets(query: string): ApiPreset[] {
  const needle = fold(query);
  if (needle === '') return API_PRESETS;
  return API_PRESETS.filter((preset) =>
    [preset.name, preset.variant ?? '', preset.summary, preset.slug, ...preset.keywords]
      .map(fold)
      .some((field) => field.includes(needle)),
  );
}

function fold(value: string): string {
  return value
    .normalize('NFD')
    .replace(/[̀-ͯ]/g, '')
    .toLowerCase()
    .trim();
}
