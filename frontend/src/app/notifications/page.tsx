"use client";

import {
  Alert,
  Box,
  Button,
  Container,
  Divider,
  FormControl,
  Grid,
  InputLabel,
  MenuItem,
  Paper,
  Select,
  Stack,
  TextField,
  Typography,
} from "@mui/material";
import SaveIcon from "@mui/icons-material/Save";
import ScienceIcon from "@mui/icons-material/Science";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { api, errorMessage } from "@/lib/api";
import {
  NotificationChannel,
  NotificationConnectionTestResult,
  NotificationDestination,
  NotificationDestinationRequest,
  NotificationTemplate,
  NotificationTemplateRequest,
} from "@/lib/types";

type Notice = { severity: "success" | "error" | "info"; text: string };

const DEFAULT_DESTINATION = {
  displayName: "",
  channel: "EMAIL" as NotificationChannel,
  provider: "AMAZON_SES_SMTP",
  host: "email-smtp.us-east-1.amazonaws.com",
  port: "587",
  tlsMode: "STARTTLS",
  fromAddress: "",
  fromName: "",
  replyTo: "",
  fromNumber: "",
  username: "",
  password: "",
  accountSid: "",
  authToken: "",
};

const DEFAULT_TEMPLATE = {
  name: "",
  channel: "EMAIL" as NotificationChannel,
  subjectTemplate: "",
  textTemplate: "",
  htmlTemplate: "",
  cssTemplate: "",
};

export default function NotificationsPage() {
  const queryClient = useQueryClient();
  const [destination, setDestination] = useState(DEFAULT_DESTINATION);
  const [template, setTemplate] = useState(DEFAULT_TEMPLATE);
  const [notice, setNotice] = useState<Notice | null>(null);

  const destinationsQuery = useQuery<NotificationDestination[]>({
    queryKey: ["notification-destinations"],
    queryFn: async () => (await api.get("/notification-destinations")).data,
  });
  const templatesQuery = useQuery<NotificationTemplate[]>({
    queryKey: ["notification-templates"],
    queryFn: async () => (await api.get("/notification-templates")).data,
  });

  const destinationMutation = useMutation({
    mutationFn: async () => {
      const request: NotificationDestinationRequest = {
        displayName: destination.displayName,
        channel: destination.channel,
        provider: destination.provider,
        configuration: destination.channel === "EMAIL"
          ? {
            host: destination.host,
            port: Number(destination.port),
            tlsMode: destination.tlsMode,
            fromAddress: destination.fromAddress,
            fromName: destination.fromName,
            replyTo: destination.replyTo,
          }
          : { fromNumber: destination.fromNumber },
        credentials: destination.channel === "EMAIL"
          ? { username: destination.username, password: destination.password }
          : { accountSid: destination.accountSid, authToken: destination.authToken },
        enabled: true,
      };
      return (await api.post<NotificationDestination>("/notification-destinations", request)).data;
    },
    onSuccess: () => {
      setDestination(DEFAULT_DESTINATION);
      setNotice({ severity: "success", text: "Notification destination saved. Test it before using it in a rule." });
      void queryClient.invalidateQueries({ queryKey: ["notification-destinations"] });
    },
    onError: (error) => setNotice({ severity: "error", text: errorMessage(error) }),
  });

  const testMutation = useMutation({
    mutationFn: async (id: number) =>
      (await api.post<NotificationConnectionTestResult>(`/notification-destinations/${id}/test`)).data,
    onSuccess: (result) => {
      setNotice({ severity: result.successful ? "success" : "error", text: result.message });
      void queryClient.invalidateQueries({ queryKey: ["notification-destinations"] });
    },
    onError: (error) => setNotice({ severity: "error", text: errorMessage(error) }),
  });

  const templateMutation = useMutation({
    mutationFn: async () => {
      const request: NotificationTemplateRequest = {
        name: template.name,
        channel: template.channel,
        subjectTemplate: template.channel === "EMAIL" ? template.subjectTemplate : null,
        textTemplate: template.textTemplate,
        htmlTemplate: template.channel === "EMAIL" ? template.htmlTemplate : null,
        cssTemplate: template.channel === "EMAIL" ? template.cssTemplate : null,
      };
      return (await api.post<NotificationTemplate>("/notification-templates", request)).data;
    },
    onSuccess: () => {
      setTemplate(DEFAULT_TEMPLATE);
      setNotice({ severity: "success", text: "Immutable template version created." });
      void queryClient.invalidateQueries({ queryKey: ["notification-templates"] });
    },
    onError: (error) => setNotice({ severity: "error", text: errorMessage(error) }),
  });

  const setDestinationValue = (key: string) => (event: React.ChangeEvent<HTMLInputElement>) => {
    setDestination((current) => ({ ...current, [key]: event.target.value }));
  };
  const setTemplateValue = (key: string) => (event: React.ChangeEvent<HTMLInputElement>) => {
    setTemplate((current) => ({ ...current, [key]: event.target.value }));
  };

  return (
    <Container maxWidth="lg" sx={{ py: 4 }}>
      <Typography variant="h5" sx={{ mb: 1 }}>Notifications</Typography>
      <Typography color="text.secondary" sx={{ mb: 3 }}>
        Configure provider connections and immutable templates before selecting EMAIL or SMS in a rule.
      </Typography>
      {notice && <Alert severity={notice.severity} sx={{ mb: 3 }}>{notice.text}</Alert>}

      <Stack spacing={3}>
        <Paper variant="outlined" sx={{ p: { xs: 2, md: 3 } }}>
          <Typography variant="h6" sx={{ mb: 0.5 }}>Add provider connection</Typography>
          <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
            Secrets are write-only and encrypted by the backend. Amazon SES can be configured through its SMTP endpoint.
          </Typography>
          <Stack spacing={2}>
            <Grid container spacing={2}>
              <Grid item xs={12} sm={4}>
                <TextField label="Display name" value={destination.displayName} onChange={setDestinationValue("displayName")} fullWidth required />
              </Grid>
              <Grid item xs={12} sm={4}>
                <FormControl fullWidth>
                  <InputLabel id="destination-channel-label">Channel</InputLabel>
                  <Select
                    labelId="destination-channel-label"
                    label="Channel"
                    value={destination.channel}
                    onChange={(event) => {
                      const channel = event.target.value as NotificationChannel;
                      setDestination((current) => ({
                        ...current,
                        channel,
                        provider: channel === "EMAIL" ? "AMAZON_SES_SMTP" : "TWILIO",
                      }));
                    }}
                  >
                    <MenuItem value="EMAIL">Email</MenuItem>
                    <MenuItem value="SMS">SMS</MenuItem>
                  </Select>
                </FormControl>
              </Grid>
              <Grid item xs={12} sm={4}>
                <TextField label="Provider" value={destination.provider} disabled fullWidth />
              </Grid>
            </Grid>

            {destination.channel === "EMAIL" ? (
              <Grid container spacing={2}>
                <Grid item xs={12} sm={8}>
                  <TextField label="SMTP host" value={destination.host} onChange={setDestinationValue("host")} fullWidth required />
                </Grid>
                <Grid item xs={12} sm={4}>
                  <TextField label="Port" type="number" value={destination.port} onChange={setDestinationValue("port")} fullWidth required />
                </Grid>
                <Grid item xs={12} sm={4}>
                  <FormControl fullWidth>
                    <InputLabel id="destination-tls-label">TLS mode</InputLabel>
                    <Select labelId="destination-tls-label" label="TLS mode" value={destination.tlsMode} onChange={(event) => setDestination((current) => ({ ...current, tlsMode: event.target.value }))}>
                      <MenuItem value="STARTTLS">STARTTLS</MenuItem>
                      <MenuItem value="SSL">SSL/TLS</MenuItem>
                      <MenuItem value="NONE">None</MenuItem>
                    </Select>
                  </FormControl>
                </Grid>
                <Grid item xs={12} sm={8}>
                  <TextField label="Verified From address" value={destination.fromAddress} onChange={setDestinationValue("fromAddress")} fullWidth required />
                </Grid>
                <Grid item xs={12} sm={6}>
                  <TextField label="From display name" value={destination.fromName} onChange={setDestinationValue("fromName")} fullWidth />
                </Grid>
                <Grid item xs={12} sm={6}>
                  <TextField label="Reply-To" value={destination.replyTo} onChange={setDestinationValue("replyTo")} fullWidth />
                </Grid>
                <Grid item xs={12} sm={6}>
                  <TextField label="SMTP username" value={destination.username} onChange={setDestinationValue("username")} fullWidth required />
                </Grid>
                <Grid item xs={12} sm={6}>
                  <TextField label="SMTP password" type="password" value={destination.password} onChange={setDestinationValue("password")} fullWidth required />
                </Grid>
              </Grid>
            ) : (
              <Grid container spacing={2}>
                <Grid item xs={12} sm={6}>
                  <TextField label="Twilio From number" value={destination.fromNumber} onChange={setDestinationValue("fromNumber")} fullWidth required helperText="Use E.164 format, for example +15551234567." />
                </Grid>
                <Grid item xs={12} sm={6}>
                  <TextField label="Twilio Account SID" value={destination.accountSid} onChange={setDestinationValue("accountSid")} fullWidth required />
                </Grid>
                <Grid item xs={12}>
                  <TextField label="Twilio Auth Token" type="password" value={destination.authToken} onChange={setDestinationValue("authToken")} fullWidth required />
                </Grid>
              </Grid>
            )}
            <Button variant="contained" startIcon={<SaveIcon />} onClick={() => destinationMutation.mutate()} disabled={destinationMutation.isPending}>
              {destinationMutation.isPending ? "Saving…" : "Save connection"}
            </Button>
          </Stack>
        </Paper>

        <Paper variant="outlined" sx={{ p: { xs: 2, md: 3 } }}>
          <Typography variant="h6" sx={{ mb: 2 }}>Configured connections</Typography>
          <Stack divider={<Divider flexItem />}>
            {!destinationsQuery.data?.length && <Typography color="text.secondary">No notification connections configured.</Typography>}
            {destinationsQuery.data?.map((item) => (
              <Stack key={item.id} direction={{ xs: "column", sm: "row" }} justifyContent="space-between" alignItems={{ sm: "center" }} spacing={1} sx={{ py: 1.5 }}>
                <Box>
                  <Typography>{item.displayName} · {item.channel}</Typography>
                  <Typography variant="body2" color="text.secondary">
                    {item.provider} · credentials {item.credentialConfigured ? "configured" : "missing"} · {item.lastTestSucceeded === true ? "last test passed" : item.lastTestSucceeded === false ? "last test failed" : "not tested"}
                  </Typography>
                </Box>
                <Button size="small" variant="outlined" startIcon={<ScienceIcon />} onClick={() => testMutation.mutate(item.id)} disabled={testMutation.isPending}>
                  Test connection
                </Button>
              </Stack>
            ))}
          </Stack>
        </Paper>

        <Paper variant="outlined" sx={{ p: { xs: 2, md: 3 } }}>
          <Typography variant="h6" sx={{ mb: 0.5 }}>Create template version</Typography>
          <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
            Use placeholders such as <code>{"{{data.first_name}}"}</code>. CSS is allowed only for email templates and is validated by the backend.
          </Typography>
          <Stack spacing={2}>
            <Grid container spacing={2}>
              <Grid item xs={12} sm={8}>
                <TextField label="Template name" value={template.name} onChange={setTemplateValue("name")} fullWidth required />
              </Grid>
              <Grid item xs={12} sm={4}>
                <FormControl fullWidth>
                  <InputLabel id="template-channel-label">Channel</InputLabel>
                  <Select labelId="template-channel-label" label="Channel" value={template.channel} onChange={(event) => setTemplate((current) => ({ ...current, channel: event.target.value as NotificationChannel }))}>
                    <MenuItem value="EMAIL">Email</MenuItem>
                    <MenuItem value="SMS">SMS</MenuItem>
                  </Select>
                </FormControl>
              </Grid>
            </Grid>
            {template.channel === "EMAIL" && <TextField label="Subject template" value={template.subjectTemplate} onChange={setTemplateValue("subjectTemplate")} fullWidth required />}
            <TextField label={template.channel === "EMAIL" ? "Plain-text body" : "SMS body"} value={template.textTemplate} onChange={setTemplateValue("textTemplate")} fullWidth required multiline minRows={5} />
            {template.channel === "EMAIL" && (
              <>
                <TextField label="HTML body" value={template.htmlTemplate} onChange={setTemplateValue("htmlTemplate")} fullWidth multiline minRows={6} helperText="Dynamic values are HTML-escaped." />
                <TextField label="CSS" value={template.cssTemplate} onChange={setTemplateValue("cssTemplate")} fullWidth multiline minRows={4} helperText="No external imports, URLs, scripts, or expressions." />
              </>
            )}
            <Button variant="contained" startIcon={<SaveIcon />} onClick={() => templateMutation.mutate()} disabled={templateMutation.isPending}>
              {templateMutation.isPending ? "Saving…" : "Create template version"}
            </Button>
          </Stack>
        </Paper>

        <Paper variant="outlined" sx={{ p: { xs: 2, md: 3 } }}>
          <Typography variant="h6" sx={{ mb: 2 }}>Templates</Typography>
          <Stack divider={<Divider flexItem />}>
            {!templatesQuery.data?.length && <Typography color="text.secondary">No notification templates configured.</Typography>}
            {templatesQuery.data?.map((item) => (
              <Box key={item.id} sx={{ py: 1.5 }}>
                <Typography>{item.name} · v{item.version} · {item.channel}</Typography>
                <Typography variant="body2" color="text.secondary">
                  {item.active ? "Active" : "Inactive"} · created {new Date(item.createdAt).toLocaleString()}
                </Typography>
              </Box>
            ))}
          </Stack>
        </Paper>
      </Stack>
    </Container>
  );
}
