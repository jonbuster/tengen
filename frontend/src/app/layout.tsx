"use client";

import { Box, CssBaseline, ThemeProvider } from "@mui/material";
import theme from "@/theme";
import { Providers } from "./providers";
import { NavBar } from "@/components/NavBar";
import { AuthProvider } from "@/lib/auth";
import "./globals.css";

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en">
      <body>
        <ThemeProvider theme={theme}>
          <CssBaseline />
          <Providers>
            <AuthProvider>
              <Box sx={{ display: "flex", minHeight: "100vh" }}>
                <NavBar />
                <Box component="main" sx={{ flexGrow: 1, bgcolor: "background.default" }}>
                  {children}
                </Box>
              </Box>
            </AuthProvider>
          </Providers>
        </ThemeProvider>
      </body>
    </html>
  );
}
