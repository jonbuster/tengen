"use client";

import { Alert, Box, Button, Link, Paper, TextField, Typography, darken, useTheme } from "@mui/material";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { useAuth } from "@/lib/auth";

export default function LoginPage() {
  const theme = useTheme();
  const { login } = useAuth();
  const router = useRouter();
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const accent = theme.palette.primary.main;
  const gradient = `linear-gradient(135deg, ${darken(accent, 0.45)} 0%, ${darken(accent, 0.35)} 50%, ${darken(accent, 0.55)} 100%)`;

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);
    setSubmitting(true);
    try {
      await login(username, password);
      router.push("/rules");
    } catch (err) {
      setError(err instanceof Error ? err.message : "Login failed");
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Box
      sx={{
        minHeight: "100vh",
        display: "flex",
        flexDirection: "column",
        alignItems: "center",
        justifyContent: "center",
        background: gradient,
      }}
    >
      <Paper sx={{ p: 4, width: 360 }}>
        <Typography variant="h5" component="h1" gutterBottom align="center">
          Tengen
        </Typography>
        <Typography variant="body2" color="text.secondary" align="center" sx={{ mb: 3 }}>
          Sign in to manage your rules and events
        </Typography>
        {error && (
          <Alert severity="error" sx={{ mb: 2 }}>
            {error}
          </Alert>
        )}
        <Box component="form" onSubmit={handleSubmit} sx={{ display: "flex", flexDirection: "column", gap: 2 }}>
          <TextField
            label="Username"
            value={username}
            onChange={(e) => setUsername(e.target.value)}
            autoComplete="username"
            fullWidth
            required
          />
          <TextField
            label="Password"
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            autoComplete="current-password"
            fullWidth
            required
          />
          <Button type="submit" variant="contained" disabled={submitting}>
            {submitting ? "Signing in..." : "Sign In"}
          </Button>
        </Box>
      </Paper>
      <Typography
        component="footer"
        variant="caption"
        sx={{ mt: 2, color: "rgba(255, 255, 255, 0.78)" }}
      >
        <Link
          href="https://github.com/jonbuster/tengen"
          target="_blank"
          rel="noopener noreferrer"
          sx={{ color: "inherit" }}
        >
          Tengen
        </Link>{" "}
        software is made by{" "}
        <Link
          href="https://github.com/jonbuster"
          target="_blank"
          rel="noopener noreferrer"
          sx={{ color: "inherit" }}
        >
          jonbuster
        </Link>
      </Typography>
    </Box>
  );
}
