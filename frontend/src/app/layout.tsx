"use client";

import { Box, Link, Paper, Typography } from "@mui/material";
import { usePathname } from "next/navigation";
import { AppThemeProvider } from "@/theme";
import { Providers } from "./providers";
import { NavBar } from "@/components/NavBar";
import { AuthProvider, useAuth } from "@/lib/auth";
import { PreferencesProvider } from "@/lib/preferences";
import "./globals.css";

function AuthenticatedPreferences({ children }: { children: React.ReactNode }) {
  const { isAuthenticated, checking } = useAuth();

  return (
    <PreferencesProvider isAuthenticated={isAuthenticated} authChecking={checking}>
      {children}
    </PreferencesProvider>
  );
}

export default function RootLayout({ children }: { children: React.ReactNode }) {
  const pathname = usePathname();
  const isLogin = pathname === "/login";

  return (
    <html lang="en">
      <body>
        <Providers>
          <AuthProvider>
            <AuthenticatedPreferences>
              <AppThemeProvider>
                <Box sx={{ display: "flex", minHeight: "100vh" }}>
                  <NavBar />
                  <Box
                    component="main"
                    sx={{
                      flexGrow: 1,
                      minWidth: 0,
                      minHeight: "100vh",
                      display: "flex",
                      flexDirection: "column",
                      bgcolor: "background.default",
                    }}
                  >
                    {isLogin ? (
                      children
                    ) : (
                      <Box
                        sx={{
                          width: "100%",
                          flexGrow: 1,
                          px: { xs: 1, sm: 2, md: 3 },
                          py: { xs: 1, md: 2 },
                        }}
                      >
                        <Paper elevation={1} sx={{ width: "100%", borderRadius: 2, overflow: "hidden" }}>
                          {children}
                        </Paper>
                      </Box>
                    )}
                    {!isLogin && (
                      <Typography
                        component="footer"
                        variant="caption"
                        align="center"
                        color="text.secondary"
                        sx={{ px: 2, pb: 2 }}
                      >
                        <Link
                          href="https://github.com/jonbuster/tengen"
                          target="_blank"
                          rel="noopener noreferrer"
                        >
                          Tengen
                        </Link>{" "}
                        software is made by{" "}
                        <Link
                          href="https://github.com/jonbuster"
                          target="_blank"
                          rel="noopener noreferrer"
                        >
                          jonbuster
                        </Link>
                        <Box
                          component="span"
                          role="img"
                          aria-label="Philippine flag"
                          sx={{ ml: 0.5, fontSize: "0.9em" }}
                        >
                          🇵🇭
                        </Box>
                      </Typography>
                    )}
                  </Box>
                </Box>
              </AppThemeProvider>
            </AuthenticatedPreferences>
          </AuthProvider>
        </Providers>
      </body>
    </html>
  );
}
