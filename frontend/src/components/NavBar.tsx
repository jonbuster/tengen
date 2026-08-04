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
  darken,
  useTheme,
} from "@mui/material";
import KeyIcon from "@mui/icons-material/Key";
import RuleIcon from "@mui/icons-material/Rule";
import ScienceIcon from "@mui/icons-material/Science";
import HistoryIcon from "@mui/icons-material/History";
import EventNoteIcon from "@mui/icons-material/EventNote";
import ReplayIcon from "@mui/icons-material/Replay";
import SettingsIcon from "@mui/icons-material/Settings";
import CableIcon from "@mui/icons-material/Cable";
import Link from "next/link";
import Image from "next/image";
import { usePathname, useRouter } from "next/navigation";
import { useAuth } from "@/lib/auth";

const DRAWER_WIDTH = 240;

const NAV_ITEMS = [
  { href: "/rules", label: "Rules", icon: <RuleIcon /> },
  { href: "/rules/test", label: "Run Test", icon: <ScienceIcon /> },
  { href: "/keys", label: "API Keys", icon: <KeyIcon /> },
  { href: "/deliveries", label: "Deliveries", icon: <HistoryIcon /> },
  { href: "/events", label: "Events", icon: <EventNoteIcon /> },
  { href: "/replays", label: "Replays", icon: <ReplayIcon /> },
  { href: "/connectors/rabbitmq", label: "Connectors", icon: <CableIcon /> },
  { href: "/settings", label: "Settings", icon: <SettingsIcon /> },
];

export function NavBar() {
  const pathname = usePathname();
  const router = useRouter();
  const theme = useTheme();
  const { logout, isAuthenticated, checking } = useAuth();

  const handleLogout = async () => {
    await logout();
    router.push("/login");
  };

  // Keep the permanent drawer mounted while the session check is in flight.
  // Otherwise protected page content paints first and the sidebar appears
  // only after /api/auth/session resolves, causing a visible layout shift.
  if ((!isAuthenticated && !checking) || pathname === "/login") {
    return null;
  }

  const sidebarTop = darken(theme.palette.primary.main, 0.45);
  const sidebarMid = darken(theme.palette.primary.main, 0.35);
  const sidebarBottom = darken(theme.palette.primary.main, 0.55);
  const sidebarText = theme.palette.getContrastText(sidebarMid);
  const overlayChannel = sidebarText.toLowerCase() === "#fff" || sidebarText.toLowerCase() === "#ffffff"
    ? "255,255,255"
    : "0,0,0";

  return (
    <Drawer
      variant="permanent"
      sx={{
        width: DRAWER_WIDTH,
        flexShrink: 0,
        "& .MuiDrawer-paper": {
          width: DRAWER_WIDTH,
          boxSizing: "border-box",
          background: `linear-gradient(180deg, ${sidebarTop} 0%, ${sidebarMid} 50%, ${sidebarBottom} 100%)`,
          color: sidebarText,
          borderRight: "none",
        },
      }}
    >
      <Box sx={{ display: "flex", flexDirection: "column", height: "100%" }}>
        <Typography variant="h6" sx={{ p: 2, fontWeight: 700 }}>
          <Link href="/rules" style={{ color: "inherit", textDecoration: "none" }}>
            <Box component="span" sx={{ display: "inline-flex", alignItems: "center", gap: 1 }}>
              <Image src="/branding/tengen-torii-24.png" alt="" width={24} height={24} priority unoptimized />
              Tengen
            </Box>
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
                    backgroundColor: active ? `rgba(${overlayChannel},0.16)` : "transparent",
                    "&:hover": { backgroundColor: `rgba(${overlayChannel},0.1)` },
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
          <Button fullWidth variant="outlined" color="inherit" onClick={handleLogout} disabled={checking}>
            {checking ? "Checking session…" : "Logout"}
          </Button>
        </Box>
      </Box>
    </Drawer>
  );
}
