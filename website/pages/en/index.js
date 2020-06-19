const React = require("react");

const CompLibrary = require("../../core/CompLibrary.js");

const variables = require(process.cwd() + "/variables.js");

const MarkdownBlock = CompLibrary.MarkdownBlock;
const Container = CompLibrary.Container;
const GridBlock = CompLibrary.GridBlock;

class HomeSplash extends React.Component {
  render() {
    const { siteConfig, language = "" } = this.props;
    const { baseUrl, docsUrl } = siteConfig;
    const docsPart = `${docsUrl ? `${docsUrl}/` : ""}`;
    const langPart = `${language ? `${language}/` : ""}`;
    const docUrl = doc => `${baseUrl}${docsPart}${langPart}${doc}`;

    const SplashContainer = props => (
      <div className="homeContainer">
        <div className="homeSplashFade">
          <div className="wrapper homeWrapper">{props.children}</div>
        </div>
      </div>
    );

    const ProjectTitle = () => (
      <h2 className="projectTitle">
        <span>
          <img className="projectTitleLogo" src={siteConfig.titleIcon} />
          {siteConfig.title}
        </span>
        <small>{siteConfig.tagline}</small>
      </h2>
    );

    const PromoSection = props => (
      <div className="section promoSection">
        <div className="promoRow">
          <div className="pluginRowBlock">{props.children}</div>
        </div>
      </div>
    );

    const Button = props => (
      <div className="pluginWrapper buttonWrapper">
        <a className="button" href={props.href} target={props.target}>
          {props.children}
        </a>
      </div>
    );

    return (
      <SplashContainer>
        <div className="inner">
          <ProjectTitle siteConfig={siteConfig} />
          <PromoSection>
            <Button href={siteConfig.apiUrl}>API Docs</Button>
            <Button href={docUrl("overview", language)}>Documentation</Button>
            <Button href={siteConfig.repoUrl}>View on GitHub</Button>
          </PromoSection>
        </div>
      </SplashContainer>
    );
  }
}

class Index extends React.Component {
  render() {
    const { config: siteConfig, language = "" } = this.props;
    const { baseUrl } = siteConfig;

    const {
      organization,
      coreModuleName,
      latestVersion,
      scalaPublishVersions
    } = variables;

    const latestVersionBadge = latestVersion
      .replace("-", "--")
      .replace("_", "__");

    const Block = props => (
      <Container
        padding={["bottom", "top"]}
        id={props.id}
        background={props.background}
      >
        <GridBlock
          align="center"
          contents={props.children}
          layout={props.layout}
        />
      </Container>
    );

    const index = `[![Maven Central](https://img.shields.io/maven-central/v/io.monix/monix-bio_2.12.svg)](https://search.maven.org/search?q=g:io.monix%20AND%20a:monix-bio_2.12) [![Join the chat at https://gitter.im/monix/monix-bio](https://badges.gitter.im/monix/monix-bio.svg)](https://gitter.im/monix/monix-bio?utm_source=badge&utm_medium=badge&utm_campaign=pr-badge&utm_content=badge)[![Snapshot](https://img.shields.io/nexus/s/https/oss.sonatype.org/io.monix/monix-bio_2.12.svg)](https://oss.sonatype.org/content/repositories/snapshots/io/monix/monix-bio_2.12/)

Asynchronous data type with typed errors.
Enhanced version of [monix.eval.Task](https://monix.io/api/3.1/monix/eval/Task.html).

### Getting Started
The latest stable version, compatible with Monix 3.x, Cats 2.x and Cats-Effect 2.x:

\`\`\`scala
libraryDependencies += "${organization}" %% "${coreModuleName}" % "${latestVersion}"
\`\`\`

Published for ScalaJS 0.6.x, 1.x, Scala ${scalaPublishVersions}.
`.trim();

    return (
      <div>
        <HomeSplash siteConfig={siteConfig} language={language} />
        <div className="mainContainer">
          <div className="index">
            <MarkdownBlock>{index}</MarkdownBlock>
          </div>
        </div>
      </div>
    );
  }
}

module.exports = Index;