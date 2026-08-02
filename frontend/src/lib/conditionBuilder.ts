export type ConditionOperator =
  | "=="
  | "!="
  | ">"
  | ">="
  | "<"
  | "<="
  | "contains"
  | "not contains";

export interface ConditionLeaf {
  id: string;
  type: "leaf";
  field: string;
  operator: ConditionOperator;
  value: string;
}

export interface ConditionGroup {
  id: string;
  type: "group";
  operator: "AND" | "OR";
  children: ConditionNode[];
}

export type ConditionNode = ConditionLeaf | ConditionGroup;

/** Suggested event fields for the autocomplete. */
export const FIELD_SUGGESTIONS = [
  "type",
  "source",
  "timestamp",
  "data.amount",
  "data.country",
  "data.currency",
  "data.status",
  "data.userId",
];

export const OPERATORS: ConditionOperator[] = [
  "==",
  "!=",
  ">",
  ">=",
  "<",
  "<=",
  "contains",
  "not contains",
];

let idCounter = 0;

export function nextId(): string {
  idCounter += 1;
  return `c${idCounter.toString(36)}_${Date.now().toString(36)}`;
}

export function createLeaf(): ConditionLeaf {
  return { id: nextId(), type: "leaf", field: "", operator: "==", value: "" };
}

export function createGroup(operator: "AND" | "OR" = "AND"): ConditionGroup {
  return { id: nextId(), type: "group", operator, children: [] };
}

/**
 * Render a value as an Aviator literal. Numbers, booleans and nil are emitted
 * bare; everything else is emitted as a single-quoted string (escaped).
 */
function quoteValue(raw: string): string {
  const value = raw.trim();
  if (value === "") return "''";
  if (!Number.isNaN(Number(value))) return value;
  if (value === "true" || value === "false") return value;
  if (value === "null" || value === "nil") return "nil";
  return `'${value.replace(/\\/g, "\\\\").replace(/'/g, "\\'")}'`;
}

function leafToAviator(leaf: ConditionLeaf): string {
  const field = leaf.field.trim();
  const value = leaf.value.trim();
  if (!field || value === "") return "";
  switch (leaf.operator) {
    case "contains":
      return `string.contains(${field}, ${quoteValue(value)})`;
    case "not contains":
      return `!string.contains(${field}, ${quoteValue(value)})`;
    default:
      return `${field} ${leaf.operator} ${quoteValue(value)}`;
  }
}

/** Recursively render a node (leaf or group) to an Aviator expression. */
export function nodeToAviator(node: ConditionNode): string {
  if (node.type === "leaf") return leafToAviator(node);
  const rendered = node.children
    .map((child) => {
      const s = nodeToAviator(child);
      if (!s) return "";
      return child.type === "group" ? `(${s})` : s;
    })
    .filter(Boolean);
  if (rendered.length === 0) return "";
  if (rendered.length === 1) return rendered[0];
  const joiner = node.operator === "AND" ? " && " : " || ";
  return rendered.join(joiner);
}

/** Render the root group to the full Aviator condition expression. */
export function generateAviator(root: ConditionGroup): string {
  const s = nodeToAviator(root);
  return s ? `(${s})` : "";
}

/* ---------------------------------- parser --------------------------------- */

type Token =
  | { kind: "lparen" }
  | { kind: "rparen" }
  | { kind: "and" }
  | { kind: "or" }
  | { kind: "not" }
  | { kind: "comma" }
  | { kind: "op"; value: Exclude<ConditionOperator, "contains" | "not contains"> }
  | { kind: "ident"; value: string }
  | { kind: "value"; value: string };

const COMPARISON_OPS = ["==", "!=", ">=", "<=", ">", "<"] as const;

function tokenize(input: string): Token[] {
  const tokens: Token[] = [];
  const src = input.trim();
  let i = 0;

  const isIdentChar = (c: string) => /[A-Za-z0-9_.$]/.test(c);
  const isNumber = (s: string) => /^-?\d+(\.\d+)?$/.test(s);

  while (i < src.length) {
    const c = src[i];
    if (/\s/.test(c)) {
      i++;
      continue;
    }
    if (c === "(") {
      tokens.push({ kind: "lparen" });
      i++;
      continue;
    }
    if (c === ")") {
      tokens.push({ kind: "rparen" });
      i++;
      continue;
    }
    if (c === ",") {
      tokens.push({ kind: "comma" });
      i++;
      continue;
    }
    if (src.startsWith("&&", i)) {
      tokens.push({ kind: "and" });
      i += 2;
      continue;
    }
    if (src.startsWith("||", i)) {
      tokens.push({ kind: "or" });
      i += 2;
      continue;
    }
    if (src.startsWith("!=", i)) {
      tokens.push({ kind: "op", value: "!=" });
      i += 2;
      continue;
    }
    if (c === "!") {
      tokens.push({ kind: "not" });
      i++;
      continue;
    }
    if (c === "'") {
      let j = i + 1;
      let value = "";
      let terminated = false;
      while (j < src.length) {
        if (src[j] === "\\" && j + 1 < src.length) {
          value += src[j + 1];
          j += 2;
          continue;
        }
        if (src[j] === "'") {
          terminated = true;
          j++;
          break;
        }
        value += src[j];
        j++;
      }
      if (!terminated) throw new Error("Unterminated string literal");
      tokens.push({ kind: "value", value });
      i = j;
      continue;
    }
    const matchedOp = COMPARISON_OPS.find((op) => src.startsWith(op, i));
    if (matchedOp) {
      tokens.push({ kind: "op", value: matchedOp });
      i += matchedOp.length;
      continue;
    }
    if (isIdentChar(c)) {
      let j = i;
      while (j < src.length && isIdentChar(src[j])) j++;
      const word = src.slice(i, j);
      if (word === "true" || word === "false" || word === "nil" || isNumber(word)) {
        tokens.push({ kind: "value", value: word });
      } else {
        tokens.push({ kind: "ident", value: word });
      }
      i = j;
      continue;
    }
    throw new Error(`Unexpected character '${c}'`);
  }
  return tokens;
}

class ParseError extends Error {}

interface Parser {
  tokens: Token[];
  pos: number;
}

function peek(p: Parser): Token | undefined {
  return p.tokens[p.pos];
}

function advance(p: Parser): Token {
  const tok = p.tokens[p.pos];
  if (!tok) throw new ParseError("Unexpected end of input");
  p.pos++;
  return tok;
}

function expectKind(p: Parser, kind: Token["kind"]): Token {
  const tok = peek(p);
  if (!tok || tok.kind !== kind) {
    throw new ParseError(`Expected '${kind}' but found '${tok?.kind ?? "end of input"}'`);
  }
  return advance(p);
}

/** Grammar: or := and ('||' and)* ; and := unary ('&&' unary)* ; unary := '!' unary | primary */
function parseOr(p: Parser): ConditionNode {
  const first = parseAnd(p);
  if (peek(p)?.kind !== "or") return first;
  const group: ConditionGroup = createGroup("OR");
  group.children.push(first);
  while (peek(p)?.kind === "or") {
    advance(p);
    group.children.push(parseAnd(p));
  }
  return group;
}

function parseAnd(p: Parser): ConditionNode {
  const first = parseUnary(p);
  if (peek(p)?.kind !== "and") return first;
  const group: ConditionGroup = createGroup("AND");
  group.children.push(first);
  while (peek(p)?.kind === "and") {
    advance(p);
    group.children.push(parseUnary(p));
  }
  return group;
}

function parseUnary(p: Parser): ConditionNode {
  if (peek(p)?.kind === "not") {
    advance(p);
    const inner = parseUnary(p);
    // Only `!string.contains(...)` is a supported negated leaf.
    if (inner.type !== "leaf" || inner.operator !== "contains") {
      throw new ParseError("Unsupported negated expression");
    }
    return { ...inner, operator: "not contains" };
  }
  return parsePrimary(p);
}

function parsePrimary(p: Parser): ConditionNode {
  const tok = peek(p);
  if (!tok) throw new ParseError("Unexpected end of input");

  if (tok.kind === "lparen") {
    advance(p);
    const node = parseOr(p);
    expectKind(p, "rparen");
    return node;
  }

  if (tok.kind === "ident" && (tok.value === "string.contains" || tok.value === "string")) {
    // string.contains(field, value)
    const combinedToken = tok.value === "string.contains";
    advance(p);
    if (!combinedToken) expectKind(p, "ident"); // contains
    expectKind(p, "lparen");
    const fieldTok = advance(p);
    if (fieldTok.kind !== "ident") throw new ParseError("Expected field identifier");
    expectKind(p, "comma");
    const valTok = advance(p);
    if (valTok.kind !== "value") throw new ParseError("Expected value");
    expectKind(p, "rparen");
    return { id: nextId(), type: "leaf", field: fieldTok.value, operator: "contains", value: valTok.value };
  }

  // Comparison: ident op value
  const fieldTok = advance(p);
  if (fieldTok.kind !== "ident") throw new ParseError("Expected field identifier");
  const opTok = advance(p);
  if (opTok.kind !== "op") throw new ParseError("Expected operator");
  const valTok = advance(p);
  if (valTok.kind !== "value") throw new ParseError("Expected value");
  return { id: nextId(), type: "leaf", field: fieldTok.value, operator: opTok.value, value: valTok.value };
}

/**
 * Attempt to parse a stored Aviator condition script back into a builder tree.
 * Returns `null` when the script isn't from our generator (e.g. hand-written
 * expressions using Aviator features the builder doesn't model).
 */
export function parseAviator(script: string): ConditionGroup | null {
  try {
    const tokens = tokenize(script);
    const p: Parser = { tokens, pos: 0 };
    const node = parseOr(p);
    if (p.pos !== tokens.length) throw new ParseError("Trailing tokens");
    // The root must be a group; wrap a bare leaf in an AND group.
    if (node.type === "leaf") {
      const group = createGroup("AND");
      group.children.push(node);
      return group;
    }
    return node;
  } catch {
    return null;
  }
}
