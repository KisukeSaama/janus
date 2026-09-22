/**
 * The file a coding agent reads before it writes a call.
 *
 * Its content is written by the server, not here. It used to be assembled in this console, which
 * meant it carried whichever address the console was opened at, and that an assistant fetching it
 * over MCP would have needed a second copy of the same template on the other side. One writer means
 * the file a person downloads and the file an assistant fetches are the same file.
 *
 * What stays here is its name, which is a contract rather than a label: the agent looks for the file
 * by it, and the page names it before the server has answered.
 */
export const AGENT_FILE_NAME = 'JANUS.md';
