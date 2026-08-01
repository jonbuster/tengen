"use client";

import {
  Box,
  Button,
  Drawer,
  List,
  ListItem,
  ListItemButton,
  ListItemIcon,
  ListItemText,
  Typography,
} from "@mui/material";
import KeyIcon from "@mui/icons-material/Key";
import RuleIcon from "@mui/icons-material/Rule";
import ScienceIcon from "@mui/icons-material/Science";
import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useAuth } from "@/lib/auth";

const DRAWER_WIDTH = 240;

const NAV_ITEMS = [
  { href: "/rules", label: "Rules", icon: <RuleIcon /> },
  { href: "/rules/test", label: "Run Test", icon: <ScienceIcon /> },
  { href: "/keys", label: "API Keys", icon: <KeyIcon /> },
];

export function NavBar() {
  const pathname = usePathname();
  const router = useRouter();
  const { logout, isAuthenticated } = useAuth();

  const handleLogout = async () => {
    await logout();
    router.push("/login");
  };

  if (!isAuthenticated || pathname === "/login") {
    return null;
  }

  return (
    <Drawer
      variant="permanent"
      sx={{
        width: DRAWER_WIDTH,
        flexShrink: 0,
        "& .MuiDrawer-paper": {
          width: DRAWER_WIDTH,
          boxSizing: "border-box",
          background: "linear-gradient(180deg, #1e3a5f 0%, #16324f 50%, #122b45 100%)",
          color: "#fff",
          borderRight: "none",
        },
      }}
    >
      <Box sx={{ display: "flex", flexDirection: "column", height: "100%" }}>
        <Typography variant="h6" sx={{ p: 2, fontWeight: 700 }}>
          <Link href="/rules" style={{ color: "inherit", textDecoration: "none" }}>
            Tengen
          </Link>
        </Typography>
        <List sx={{ px: 1 }}>
          {NAV_ITEMS.map((item) => {
            const active = pathname.startsWith(item.href);
            return (
              <ListItem key={item.href} disablePadding sx={{ mb: 0.5 }}>
                <ListItemButton
                  component={Link}
                  href={item.href}
                  sx={{
                    borderRadius: 1,
                    fontWeight: active ? 700 : 400,
                    backgroundColor: active ? "rgba(255,255,255,0.12)" : "transparent",
                    "&:hover": { backgroundColor: "rgba(255,255,255,0.08)" },
                  }}
                >
                  <ListItemIcon sx={{ color: "inherit", minWidth: 36 }}>
                    {item.icon}
                  </ListItemIcon>
                  <ListItemText primary={item.label} />
                </ListItemButton>
              </ListItem>
            );
          })}
        </List>
        <Box sx={{ flexGrow: 1 }} />
        <Box sx={{ p: 2 }}>
          <Button fullWidth variant="outlined" color="inherit" onClick={handleLogout}>
            Logout
          </Button>
        </Box>
      </Box>
    </Drawer>
  );
}
