const repoUrl = "https://github.com/monix/monix-bio";

const apiUrl = "/monix-bio/api/monix/bio/index.html"

const siteConfig = {
  title: "Monix BIO",
  tagline: "Asynchronous Programming for Scala and Scala.js",
  url: "https://monix.github.io/monix-bio",
  baseUrl: "/monix-bio/",

  customDocsPath: "monix-bio-docs/target/mdoc",

  projectName: "monix-bio",
  organizationName: "monix",

  headerLinks: [
    { href: apiUrl, label: "API Docs" },
    { doc: "overview", label: "Documentation" },
    { href: repoUrl, label: "GitHub" }
  ],

  headerIcon: "img/monix-logo.svg",
  titleIcon: "img/monix-logo.svg",
  favicon: "img/monix-logo.png",

  colors: {
    primaryColor: "#122932",
    secondaryColor: "#153243"
  },

  copyright: `Copyright © 2019-${new Date().getFullYear()} The Monix Project Developers.`,

  highlight: { theme: "github" },

  onPageNav: "separate",

  separateCss: ["api"],

  cleanUrl: true,

  repoUrl,

  apiUrl
};

module.exports = siteConfig;
