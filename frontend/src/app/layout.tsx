"use client";

import { Box, CssBaseline, Paper, ThemeProvider } from "@mui/material";
import { usePathname } from "next/navigation";
import theme from "@/theme";
import { Providers } from "./providers";
import { NavBar } from "@/components/NavBar";
import { AuthProvider } from "@/lib/auth";
import "./globals.css";

export default function RootLayout({ children }: { children: React.ReactNode }) {
  const pathname = usePathname();
  const isLogin = pathname === "/login";

  return (
    <html lang="en">
      <body>
        <ThemeProvider theme={theme}>
          <CssBaseline />
          <Providers>
            <AuthProvider>
              <Box sx={{ display: "flex", minHeight: "100vh" }}>
                <NavBar />
                <Box component="main" sx={{ flexGrow: 1, minWidth: 0, bgcolor: "background.default" }}>
                  {isLogin ? (
                    children
                  ) : (
                    <Box sx={{ width: "100%", px: { xs: 1, sm: 2, md: 3 }, py: { xs: 1, md: 2 } }}>
                      <Paper elevation={1} sx={{ width: "100%", borderRadius: 2, overflow: "hidden" }}>
                        {children}
                      </Paper>
                    </Box>
                  )}
                </Box>
              </Box>
            </AuthProvider>
          </Providers>
        </ThemeProvider>
      </body>
    </html>
  );
}
