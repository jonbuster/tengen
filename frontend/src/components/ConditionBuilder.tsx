"use client";

import {
  Autocomplete,
  Box,
  Button,
  FormControl,
  IconButton,
  InputLabel,
  MenuItem,
  Paper,
  Select,
  TextField,
  Typography,
} from "@mui/material";
import AddIcon from "@mui/icons-material/Add";
import DeleteOutlineIcon from "@mui/icons-material/DeleteOutline";
import { useMemo, useState } from "react";
import {
  ConditionGroup,
  ConditionLeaf,
  ConditionNode,
  ConditionOperator,
  FIELD_SUGGESTIONS,
  OPERATORS,
  createGroup,
  createLeaf,
  generateAviator,
} from "@/lib/conditionBuilder";

interface ConditionBuilderProps {
  root: ConditionGroup;
  onChange: (root: ConditionGroup) => void;
}

export function ConditionBuilder({ root, onChange }: ConditionBuilderProps) {
  const preview = useMemo(() => generateAviator(root), [root]);

  const addChild = (id: string, child: ConditionNode) => {
    onChange({
      ...root,
      children: insertInto(root, id, child),
    });
  };

  const removeChild = (id: string) => {
    onChange({ ...root, children: removeFrom(root, id) });
  };

  const updateChild = (id: string, updater: (node: ConditionNode) => ConditionNode) => {
    onChange({ ...root, children: updateIn(root, id, updater) });
  };

  return (
    <Box sx={{ display: "flex", flexDirection: "column", gap: 2 }}>
      <GroupEditor
        group={root}
        onChange={onChange}
        addChild={addChild}
        removeChild={removeChild}
        updateChild={updateChild}
        root
      />

      <Paper variant="outlined" sx={{ p: 1.5, bgcolor: "action.hover" }}>
        <Typography variant="caption" color="text.secondary" sx={{ display: "block", mb: 0.5 }}>
          Generated Aviator expression
        </Typography>
        <Typography variant="body2" sx={{ fontFamily: "monospace" }}>
          {preview || "— no conditions yet —"}
        </Typography>
      </Paper>
    </Box>
  );
}

/* ---------------------------------- utils --------------------------------- */

function insertInto(node: ConditionGroup, targetId: string, child: ConditionNode): ConditionNode[] {
  if (node.id === targetId) return [...node.children, child];
  return node.children.map((c) =>
    c.type === "group" ? { ...c, children: insertInto(c, targetId, child) } : c,
  );
}

function removeFrom(node: ConditionGroup, targetId: string): ConditionNode[] {
  return node.children
    .filter((c) => c.id !== targetId)
    .map((c) => (c.type === "group" ? { ...c, children: removeFrom(c, targetId) } : c));
}

function updateIn(
  node: ConditionGroup,
  targetId: string,
  updater: (node: ConditionNode) => ConditionNode,
): ConditionNode[] {
  return node.children.map((c) => {
    if (c.id === targetId) return updater(c);
    if (c.type === "group") return { ...c, children: updateIn(c, targetId, updater) };
    return c;
  });
}

/* -------------------------------- group editor ----------------------------- */

interface GroupEditorProps {
  group: ConditionGroup;
  onChange: (group: ConditionGroup) => void;
  addChild: (groupId: string, child: ConditionNode) => void;
  removeChild: (childId: string) => void;
  updateChild: (childId: string, updater: (node: ConditionNode) => ConditionNode) => void;
  root?: boolean;
}

function GroupEditor({ group, onChange, addChild, removeChild, updateChild, root }: GroupEditorProps) {
  const groupCount = group.children.filter((c) => c.type === "group").length;
  const leafCount = group.children.filter((c) => c.type === "leaf").length;

  return (
    <Paper variant={root ? "outlined" : "elevation"} elevation={root ? 0 : 1} sx={{ p: 1.5, mb: 1.5 }}>
      <Box sx={{ display: "flex", alignItems: "center", gap: 1, mb: 1 }}>
        <Typography variant="body2" sx={{ fontWeight: 600 }}>
          Match
        </Typography>
        <FormControl size="small" sx={{ minWidth: 90 }}>
          <Select
            value={group.operator}
            onChange={(e) =>
              onChange({ ...group, operator: e.target.value as ConditionGroup["operator"] })
            }
            sx={{ bgcolor: "background.paper" }}
          >
            <MenuItem value="AND">AND</MenuItem>
            <MenuItem value="OR">OR</MenuItem>
          </Select>
        </FormControl>
        <Typography variant="body2" color="text.secondary" sx={{ ml: 1 }}>
          {leafCount} condition{leafCount === 1 ? "" : "s"}
          {groupCount > 0 ? `, ${groupCount} group${groupCount === 1 ? "" : "s"}` : ""}
        </Typography>
      </Box>

      {group.children.length === 0 && (
        <Typography variant="body2" color="text.secondary" sx={{ py: 1 }}>
          No conditions yet — add one below.
        </Typography>
      )}

      {group.children.map((child) =>
        child.type === "leaf" ? (
          <LeafRow
            key={child.id}
            leaf={child}
            onRemove={() => removeChild(child.id)}
            onChange={(leaf) => updateChild(child.id, () => leaf)}
          />
        ) : (
          <Box key={child.id} sx={{ display: "flex", alignItems: "flex-start", gap: 0.5 }}>
            <Box sx={{ flexGrow: 1 }}>
              <GroupEditor
                group={child}
                onChange={(g) => updateChild(child.id, () => g)}
                addChild={addChild}
                removeChild={removeChild}
                updateChild={updateChild}
              />
            </Box>
            <IconButton size="small" onClick={() => removeChild(child.id)} aria-label="Remove group">
              <DeleteOutlineIcon fontSize="small" />
            </IconButton>
          </Box>
        ),
      )}

      <Box sx={{ display: "flex", gap: 1, mt: 1 }}>
        <Button size="small" startIcon={<AddIcon />} onClick={() => addChild(group.id, createLeaf())}>
          Add condition
        </Button>
        <Button size="small" startIcon={<AddIcon />} onClick={() => addChild(group.id, createGroup())}>
          Add group
        </Button>
      </Box>
    </Paper>
  );
}

/* --------------------------------- leaf row -------------------------------- */

interface LeafRowProps {
  leaf: ConditionLeaf;
  onChange: (leaf: ConditionLeaf) => void;
  onRemove: () => void;
}

function LeafRow({ leaf, onChange, onRemove }: LeafRowProps) {
  const [fieldInput, setFieldInput] = useState(leaf.field);

  return (
    <Box sx={{ display: "flex", alignItems: "center", gap: 1, mb: 1.5 }}>
      <Autocomplete
        freeSolo
        size="small"
        options={FIELD_SUGGESTIONS}
        inputValue={fieldInput}
        onInputChange={(_, value) => {
          setFieldInput(value);
          onChange({ ...leaf, field: value });
        }}
        onChange={(_, value) => {
          const v = value ?? "";
          setFieldInput(v);
          onChange({ ...leaf, field: v });
        }}
        renderInput={(params) => (
          <TextField {...params} label="Field" placeholder="e.g. data.amount" size="small" />
        )}
        sx={{ width: 220 }}
      />

      <FormControl size="small" sx={{ minWidth: 140 }}>
        <InputLabel>Operator</InputLabel>
        <Select
          label="Operator"
          value={leaf.operator}
          onChange={(e) => onChange({ ...leaf, operator: e.target.value as ConditionOperator })}
        >
          {OPERATORS.map((op) => (
            <MenuItem key={op} value={op}>
              {op}
            </MenuItem>
          ))}
        </Select>
      </FormControl>

      <TextField
        size="small"
        label="Value"
        value={leaf.value}
        onChange={(e) => onChange({ ...leaf, value: e.target.value })}
        sx={{ flexGrow: 1 }}
      />

      <IconButton size="small" onClick={onRemove} aria-label="Remove condition">
        <DeleteOutlineIcon fontSize="small" />
      </IconButton>
    </Box>
  );
}
