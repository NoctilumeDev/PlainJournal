import { createApp } from "vue";

import "@plain-journal/design-system/tokens.css";
import "@plain-journal/design-system/base.css";
import "@plain-journal/ui/styles.css";
import "./shared/ui/styles.css";
import "./styles/storefront.css";

import App from "./App.vue";
import { pinia } from "./app/pinia";
import { router } from "./app/router";
import { useThemeStore } from "./features/theme";

const app = createApp(App);

app.use(pinia);
useThemeStore(pinia).initialize();

app
  .use(router)
  .mount("#app");
