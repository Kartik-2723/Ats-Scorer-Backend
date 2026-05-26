package com.resumeshaper.llm;

import java.util.Map;

/**
 * Single source of truth for all default job descriptions.
 *
 * HOW TO ADD A NEW ROLE:
 *  1. Add the role to the ROLES list in your RoleConfig (or wherever roles are defined).
 *  2. Add a Map.entry(...) here with the exact same role label string.
 *  3. That's it — PromptBuilder picks it up automatically.
 *
 * HOW TO UPDATE A JD:
 *  Just edit the text block below for that role. No other file needs to change.
 */
public final class JobDescriptions {

    private JobDescriptions() {} // utility class — no instantiation

    // ── Lookup ────────────────────────────────────────────────────────────────

    private static final Map<String, String> JDS = Map.ofEntries(

            // ── Engineering ───────────────────────────────────────────────────────

            Map.entry("Software Engineer", """
                    Overview :-
                    
                    A Software Engineer is responsible for designing, developing, testing, and maintaining software applications and systems.
                    They work closely with cross-functional teams to create efficient, scalable, and reliable software solutions that meet user and business requirements.
                    
                    Responsibilities :-
                    
                    Design and develop software applications and systems
                    Write clean, efficient, and maintainable code
                    Test, debug, and troubleshoot software issues
                    Collaborate with designers, developers, and project managers
                    Maintain and improve existing applications
                    Participate in code reviews and technical discussions
                    Ensure software performance, security, and scalability
                    Document software features and technical processes
                    
                    Required Skills :-
                    
                    Proficiency in programming languages such as Java, Python, C++, or JavaScript
                    Knowledge of Data Structures and Algorithms
                    Understanding of Object-Oriented Programming concepts
                    Familiarity with databases like MySQL, PostgreSQL, or MongoDB
                    Experience with version control systems such as Git
                    Strong problem-solving and analytical skills
                    Good communication and teamwork abilities
            """),

            Map.entry("Frontend Developer", """
                    Overview :-
                    
                    A Frontend Developer is responsible for designing and developing the user interface and user experience of web applications and websites.
                    They ensure websites are visually appealing, responsive, interactive, and user-friendly across different devices and browsers.
                    
                    Responsibilities :-
                    
                    Develop responsive and interactive web pages
                    Convert UI/UX designs into functional web applications
                    Write clean, maintainable, and efficient frontend code
                    Optimize applications for speed and performance
                    Ensure cross-browser and mobile compatibility
                    Collaborate with backend developers, designers, and project managers
                    Debug and fix frontend issues
                    Maintain and improve existing web applications
                    
                    Required Skills :-
                    
                    Proficiency in HTML, CSS, and JavaScript
                    Experience with frontend frameworks such as React, Angular, or Vue.js
                    Understanding of responsive web design principles
                    Knowledge of version control systems like Git
                    Familiarity with REST APIs and API integration
                    Basic understanding of UI/UX principles
                    Strong problem-solving and debugging skills
                    Good communication and teamwork abilities
            """),

            Map.entry("Backend Developer", """
                    Overview :-
                    
                    A Backend Developer is responsible for developing and maintaining the server-side logic, databases, APIs, and core functionality of web applications.
                    They ensure applications are secure, scalable, efficient, and capable of handling large amounts of data and user requests.
                    
                    Responsibilities :-
                    
                    Develop and maintain server-side applications and APIs
                    Design and manage databases and data storage solutions
                    Implement authentication, authorization, and security features
                    Optimize backend performance and scalability
                    Integrate frontend applications with backend services
                    Debug, test, and troubleshoot backend issues
                    Collaborate with frontend developers and project teams
                    Maintain server infrastructure and application reliability
                    
                    Required Skills :-
                    
                    Proficiency in backend programming languages such as Java, Python, Node.js, PHP, or Go
                    Knowledge of frameworks like Spring Boot, Express.js, Django, or Laravel
                    Strong understanding of databases such as MySQL, PostgreSQL, or MongoDB
                    Familiarity with REST APIs and microservices architecture
                    Knowledge of Git and version control systems
                    Understanding of authentication, security, and cloud services
                    Strong problem-solving and debugging skills
                    Good communication and teamwork abilities
            """),

            Map.entry("Full Stack Developer", """
                    Overview :-
                    
                    A Full Stack Developer is responsible for developing both frontend and backend components of web applications.
                    They handle complete application development including user interface, server-side logic, databases, and API integration.
                    
                    Responsibilities :-
                    
                    Develop responsive frontend interfaces and backend systems
                    Build and maintain APIs and databases
                    Design and implement complete web applications
                    Collaborate with designers, developers, and project managers
                    Optimize application performance and scalability
                    Debug, test, and troubleshoot application issues
                    Ensure application security and data protection
                    Maintain and improve existing applications
                    
                    Required Skills :-
                    
                    Proficiency in HTML, CSS, JavaScript, and modern frontend frameworks like React or Angular
                    Strong knowledge of backend technologies such as Node.js, Spring Boot, Django, or Laravel
                    Understanding of databases like MySQL, PostgreSQL, or MongoDB
                    Familiarity with REST APIs and API integration
                    Knowledge of Git and version control systems
                    Understanding of deployment, hosting, and cloud platforms
                    Strong problem-solving and debugging skills
                    Good communication and teamwork abilities
            """),

            Map.entry("DevOps / SRE", """
                    Overview :-
                    
                    A DevOps Engineer or Site Reliability Engineer (SRE) is responsible for automating, monitoring, deploying, and maintaining software infrastructure and production systems.
                    They ensure applications and services are reliable, scalable, secure, and highly available.
                    
                    Responsibilities :-
                    
                    Automate deployment and infrastructure management processes
                    Monitor system performance, uptime, and reliability
                    Manage CI/CD pipelines for automated testing and deployment
                    Configure and maintain cloud infrastructure and servers
                    Troubleshoot production issues and system failures
                    Improve application scalability, security, and performance
                    Collaborate with development and operations teams
                    Implement logging, monitoring, and alerting systems
                    
                    Required Skills :-
                    
                    Knowledge of Linux systems and shell scripting
                    Experience with cloud platforms such as AWS, Azure, or Google Cloud
                    Familiarity with Docker, Kubernetes, and containerization
                    Understanding of CI/CD tools like Jenkins, GitHub Actions, or GitLab CI/CD
                    Knowledge of Infrastructure as Code tools like Terraform or Ansible
                    Familiarity with monitoring tools such as Prometheus, Grafana, or ELK Stack
                    Strong troubleshooting and problem-solving skills
                    Understanding of networking, security, and system architecture
            """),

            Map.entry("Mobile Developer (iOS)", """
                    Overview :-
                    
                    An iOS Developer is responsible for designing, developing, and maintaining mobile applications for Apple devices such as iPhones and iPads.
                    They create high-performance, user-friendly, and secure applications using Apple technologies and frameworks.
                    
                    Responsibilities :-
                    
                    Develop and maintain iOS mobile applications
                    Write clean, efficient, and maintainable Swift or Objective-C code
                    Design responsive and interactive mobile user interfaces
                    Integrate APIs and backend services into applications
                    Test, debug, and optimize application performance
                    Ensure application compatibility across iOS devices and versions
                    Collaborate with designers, backend developers, and product teams
                    Publish and maintain applications on the Apple App Store
                    
                    Required Skills :-
                    
                    Proficiency in Swift and basic knowledge of Objective-C
                    Experience with Xcode and iOS SDK
                    Understanding of UIKit, SwiftUI, and Apple frameworks
                    Familiarity with REST APIs and JSON data handling
                    Knowledge of Git and version control systems
                    Understanding of mobile app architecture and design patterns
                    Strong debugging and problem-solving skills
                    Good communication and teamwork abilities
            """),

            Map.entry("Mobile Developer (Android)", """
                    Overview :-
                    
                    An Android Developer is responsible for designing, developing, and maintaining mobile applications for Android devices.
                    They create scalable, user-friendly, and high-performance applications using modern Android technologies and frameworks.
                    
                    Responsibilities :-
                    
                    Develop and maintain Android mobile applications
                    Write clean, efficient, and maintainable Kotlin or Java code
                    Design responsive and interactive mobile user interfaces
                    Integrate APIs and backend services into applications
                    Test, debug, and optimize application performance
                    Ensure compatibility across different Android devices and versions
                    Collaborate with designers, backend developers, and product teams
                    Publish and maintain applications on the Google Play Store
                    
                    Required Skills :-
                    
                    Proficiency in Kotlin and Java
                    Experience with Android Studio and Android SDK
                    Understanding of Jetpack libraries and Android architecture components
                    Familiarity with REST APIs and JSON data handling
                    Knowledge of Firebase and mobile databases
                    Experience with Git and version control systems
                    Strong debugging and problem-solving skills
                    Good communication and teamwork abilities
            """),

            Map.entry("QA / SDET", """
                    Overview :-
                    
                    A QA Engineer or Software Development Engineer in Test (SDET) is responsible for ensuring software quality through manual and automated testing processes.
                    They identify bugs, improve testing frameworks, and ensure applications meet functional, performance, and security requirements before release.
                    
                    Responsibilities :-
                    
                    Design and execute test cases and test plans
                    Perform manual and automated testing of applications
                    Identify, document, and track software defects
                    Develop and maintain automated testing frameworks
                    Collaborate with developers and product teams to resolve issues
                    Conduct regression, integration, API, and performance testing
                    Ensure software quality, reliability, and usability
                    Maintain testing documentation and reports
                    
                    Required Skills :-
                    
                    Knowledge of software testing methodologies and QA processes
                    Experience with automation testing tools such as Selenium, Cypress, or Playwright
                    Familiarity with programming languages like Java, Python, or JavaScript
                    Understanding of API testing using Postman or similar tools
                    Knowledge of CI/CD pipelines and version control systems like Git
                    Strong analytical and debugging skills
                    Understanding of databases and SQL queries
                    Good communication and teamwork abilities
            """),

            Map.entry("Security Engineer", """
                    Overview :-
                    
                    A Security Engineer is responsible for protecting software systems, networks, and applications from cyber threats and security vulnerabilities.
                    They design, implement, and maintain security measures to ensure data protection, system integrity, and secure application development.
                    
                    Responsibilities :-
                    
                    Monitor systems and networks for security threats and vulnerabilities
                    Implement security controls, firewalls, and encryption mechanisms
                    Conduct vulnerability assessments and penetration testing
                    Identify and resolve security risks in applications and infrastructure
                    Develop and enforce security policies and best practices
                    Respond to security incidents and perform root cause analysis
                    Collaborate with development and DevOps teams for secure deployments
                    Maintain security logs, reports, and compliance documentation
                    
                    Required Skills :-
                    
                    Knowledge of cybersecurity principles and network security
                    Familiarity with security tools such as Wireshark, Metasploit, or Burp Suite
                    Understanding of firewalls, VPNs, IDS/IPS, and encryption techniques
                    Experience with Linux systems and cloud security
                    Knowledge of secure coding practices and vulnerability management
                    Familiarity with penetration testing and ethical hacking concepts
                    Strong analytical and problem-solving skills
                    Good communication and teamwork abilities
            """),

            Map.entry("Cloud Architect", """
                    Overview :-
                    
                    A Cloud Architect is responsible for designing, managing, and optimizing cloud infrastructure and cloud-based solutions for organizations.
                    They ensure cloud systems are scalable, secure, reliable, and cost-efficient while supporting business and technical requirements.
                    
                    Responsibilities :-
                    
                    Design and implement cloud infrastructure and architecture solutions
                    Manage cloud platforms such as AWS, Azure, or Google Cloud
                    Develop scalable and secure cloud deployment strategies
                    Optimize cloud performance, reliability, and cost management
                    Collaborate with developers, DevOps engineers, and IT teams
                    Implement cloud security and disaster recovery solutions
                    Monitor and troubleshoot cloud infrastructure issues
                    Maintain documentation for cloud architecture and processes
                    
                    Required Skills :-
                    
                    Strong knowledge of cloud platforms such as AWS, Azure, or Google Cloud
                    Understanding of networking, virtualization, and distributed systems
                    Experience with Docker, Kubernetes, and container orchestration
                    Familiarity with Infrastructure as Code tools like Terraform or CloudFormation
                    Knowledge of cloud security, monitoring, and compliance practices
                    Understanding of CI/CD pipelines and DevOps practices
                    Strong analytical and problem-solving skills
                    Good communication and leadership abilities
            """),

            // ── Data ──────────────────────────────────────────────────────────────

            Map.entry("Data Engineer", """
                    Overview :-
                    
                    A Data Engineer is responsible for designing, building, and maintaining data pipelines, databases, and large-scale data processing systems.
                    They ensure data is collected, transformed, stored, and made accessible for analytics, reporting, and machine learning applications.
                    
                    Responsibilities :-
                    
                    Design and maintain scalable data pipelines and ETL processes
                    Collect, clean, transform, and manage large datasets
                    Build and optimize data storage solutions and databases
                    Integrate data from multiple sources and systems
                    Ensure data quality, consistency, and reliability
                    Collaborate with data analysts, data scientists, and software engineers
                    Monitor and troubleshoot data processing systems
                    Implement data security and governance practices
                    
                    Required Skills :-
                    
                    Proficiency in SQL and database management systems
                    Experience with programming languages such as Python, Java, or Scala
                    Knowledge of big data technologies like Hadoop, Spark, or Kafka
                    Familiarity with cloud platforms such as AWS, Azure, or Google Cloud
                    Understanding of ETL tools and data warehousing concepts
                    Experience with relational and NoSQL databases
                    Strong analytical and problem-solving skills
                    Good communication and teamwork abilities
            """),

            Map.entry("Data Scientist", """
                    Overview :-
                    
                    A Data Scientist is responsible for analyzing large datasets to extract insights, build predictive models, and support data-driven decision making.
                    They use statistics, machine learning, and data analysis techniques to solve complex business and technical problems.
                    
                    Responsibilities :-
                    
                    Collect, clean, and analyze structured and unstructured data
                    Build machine learning and predictive models
                    Identify trends, patterns, and business insights from data
                    Develop data visualization dashboards and reports
                    Collaborate with data engineers, analysts, and business teams
                    Optimize model performance and accuracy
                    Perform statistical analysis and hypothesis testing
                    Communicate findings and recommendations to stakeholders
                    
                    Required Skills :-
                    
                    Proficiency in Python, R, or SQL
                    Knowledge of machine learning algorithms and data analysis techniques
                    Experience with libraries such as Pandas, NumPy, Scikit-learn, or TensorFlow
                    Understanding of statistics and probability concepts
                    Familiarity with data visualization tools like Tableau or Power BI
                    Experience with big data technologies and cloud platforms is beneficial
                    Strong analytical and problem-solving skills
                    Good communication and presentation abilities
            """),

            // ── AI / ML ───────────────────────────────────────────────────────────

            Map.entry("ML Engineer", """
                    Overview :-
                    
                    A Machine Learning Engineer is responsible for designing, building, deploying, and maintaining machine learning models and AI systems.
                    They combine software engineering and machine learning expertise to create scalable and production-ready AI solutions.
                    
                    Responsibilities :-
                    
                    Develop, train, and deploy machine learning models
                    Build data pipelines and model training workflows
                    Optimize model performance, scalability, and accuracy
                    Integrate machine learning models into applications and APIs
                    Collaborate with data scientists, data engineers, and software developers
                    Monitor and maintain production AI systems
                    Perform data preprocessing and feature engineering
                    Research and implement new machine learning techniques and tools
                    
                    Required Skills :-
                    
                    Proficiency in Python, Java, or C++
                    Strong understanding of machine learning and deep learning algorithms
                    Experience with frameworks such as TensorFlow, PyTorch, or Scikit-learn
                    Knowledge of data structures, algorithms, and software engineering principles
                    Familiarity with cloud platforms like AWS, Azure, or Google Cloud
                    Experience with Docker, Kubernetes, and MLOps tools is beneficial
                    Strong analytical and problem-solving skills
                    Good communication and teamwork abilities
            """),

            Map.entry("AI/LLM Engineer", """
                    Overview :-
                    
                    An AI / LLM Engineer is responsible for developing, fine-tuning, deploying, and optimizing Artificial Intelligence systems and Large Language Model (LLM) applications.
                    They build intelligent AI-powered solutions such as chatbots, AI assistants, recommendation systems, and generative AI applications.
                    
                    Responsibilities :-
                    
                    Develop and deploy AI and LLM-based applications
                    Fine-tune and optimize machine learning and language models
                    Build AI pipelines using APIs, embeddings, vector databases, and RAG systems
                    Integrate AI models into web, mobile, or enterprise applications
                    Monitor AI model performance, scalability, and reliability
                    Work with prompt engineering and AI response optimization
                    Collaborate with data scientists, backend developers, and product teams
                    Research and implement new AI technologies and frameworks
                    
                    Required Skills :-
                    
                    Proficiency in Python and AI-related programming libraries
                    Strong understanding of machine learning, deep learning, and NLP concepts
                    Experience with frameworks such as TensorFlow, PyTorch, Hugging Face, or LangChain
                    Familiarity with LLMs such as GPT, Claude, Gemini, or open-source models
                    Knowledge of vector databases like Pinecone, Weaviate, or ChromaDB
                    Understanding of APIs, cloud platforms, and model deployment
                    Strong problem-solving and debugging skills
                    Good communication and teamwork abilities
            """),

            // ── Product ───────────────────────────────────────────────────────────

            Map.entry("Product Manager", """
                    Overview :-
                    
                    A Product Manager is responsible for planning, developing, and managing products throughout their lifecycle.
                    They work closely with engineering, design, marketing, and business teams to deliver products that meet customer needs and business goals.
                    
                    Responsibilities :-
                    
                    Define product vision, strategy, and roadmap
                    Gather and analyze customer and market requirements
                    Collaborate with developers, designers, and stakeholders
                    Prioritize product features and manage product backlog
                    Coordinate product development and release processes
                    Monitor product performance and user feedback
                    Conduct market research and competitor analysis
                    Ensure products align with business objectives and customer expectations
                    
                    Required Skills :-
                    
                    Strong understanding of product development and management processes
                    Excellent communication and leadership abilities
                    Knowledge of Agile and Scrum methodologies
                    Ability to analyze data and make strategic decisions
                    Familiarity with tools such as Jira, Trello, or Notion
                    Understanding of user experience and market trends
                    Strong problem-solving and organizational skills
                    Ability to work with cross-functional teams effectively
            """),

            Map.entry("Scrum Master / Agile Coach", """
                    Overview :-
                    
                    A Scrum Master or Agile Coach is responsible for guiding teams in adopting Agile methodologies and improving collaboration, productivity, and project delivery processes.
                    They help teams follow Agile principles, remove obstacles, and ensure smooth execution of projects using frameworks such as Scrum or Kanban.
                    
                    Responsibilities :-
                    
                    Facilitate Agile ceremonies such as daily stand-ups, sprint planning, reviews, and retrospectives
                    Guide teams in implementing Scrum and Agile best practices
                    Remove blockers and resolve team workflow issues
                    Support collaboration between developers, product managers, and stakeholders
                    Monitor team progress and improve productivity
                    Coach teams on Agile mindset and continuous improvement
                    Ensure transparency and effective communication within teams
                    Track project timelines, sprint goals, and delivery performance
                    
                    Required Skills :-
                    
                    Strong understanding of Agile, Scrum, and Kanban methodologies
                    Excellent communication and leadership abilities
                    Experience with Agile project management tools such as Jira or Trello
                    Ability to resolve conflicts and improve team collaboration
                    Knowledge of software development lifecycle and project workflows
                    Strong organizational and problem-solving skills
                    Ability to mentor and coach development teams
                    Understanding of metrics, sprint planning, and backlog management
            """),

            // ── Design ────────────────────────────────────────────────────────────

            Map.entry("Product Designer / UX", """
                    Overview :-
                    
                    A Product Designer / UX Designer is responsible for creating intuitive, user-friendly, and visually appealing digital experiences for web and mobile applications.
                    They focus on understanding user needs, improving usability, and designing products that enhance customer satisfaction and business goals.
                    
                    Responsibilities :-
                    
                    Conduct user research and analyze user behavior
                    Create wireframes, prototypes, and user flows
                    Design responsive and engaging user interfaces
                    Collaborate with product managers, developers, and stakeholders
                    Perform usability testing and improve user experience
                    Maintain design systems and UI consistency across products
                    Optimize accessibility, usability, and interaction design
                    Translate business and user requirements into design solutions
                    
                    Required Skills :-
                    
                    Proficiency in Figma, Adobe XD, Sketch, or similar design tools
                    Strong understanding of UX principles, interaction design, and usability
                    Knowledge of wireframing, prototyping, and design systems
                    Understanding of responsive and mobile-first design
                    Familiarity with user research and usability testing methods
                    Basic understanding of HTML, CSS, and frontend development is beneficial
                    Strong creativity, analytical thinking, and problem-solving skills
                    Good communication and collaboration abilities
            """),

            Map.entry("UI Designer", """
                    Overview :-
                    
                    A UI Designer is responsible for designing visually appealing, interactive, and user-friendly interfaces for web and mobile applications.
                    They focus on the look, feel, layout, and visual consistency of digital products to enhance user engagement and brand identity.
                    
                    Responsibilities :-
                    
                    Design user interfaces for web and mobile applications
                    Create layouts, icons, buttons, typography, and visual assets
                    Develop responsive and visually consistent designs
                    Collaborate with UX designers, developers, and product managers
                    Maintain design systems and style guides
                    Ensure designs align with branding and accessibility standards
                    Create high-fidelity mockups and prototypes
                    Improve interface aesthetics and user interaction experiences
                    
                    Required Skills :-
                    
                    Proficiency in design tools such as Figma, Adobe XD, Sketch, or Photoshop
                    Strong understanding of color theory, typography, and visual hierarchy
                    Knowledge of responsive and mobile-first design principles
                    Familiarity with design systems and component-based design
                    Basic understanding of HTML, CSS, and frontend workflows is beneficial
                    Strong creativity and attention to detail
                    Good communication and teamwork abilities
                    Understanding of accessibility and modern UI trends
            """),

            // ── Business ──────────────────────────────────────────────────────────

            Map.entry("Business Analyst", """
                    Overview :-
                    
                    A Business Analyst is responsible for analyzing business processes, identifying requirements, and bridging the gap between business stakeholders and technical teams.
                    They help organizations improve efficiency, solve business problems, and implement effective technology solutions.
                    
                    Responsibilities :-
                    
                    Gather, analyze, and document business requirements
                    Communicate requirements between stakeholders and development teams
                    Analyze business processes and identify areas for improvement
                    Create reports, workflows, and functional documentation
                    Support project planning, testing, and implementation activities
                    Conduct market and business data analysis
                    Collaborate with product managers, developers, and clients
                    Ensure solutions align with business goals and user needs
                    
                    Required Skills :-
                    
                    Strong analytical and problem-solving abilities
                    Excellent communication and presentation skills
                    Knowledge of business analysis methodologies and tools
                    Familiarity with SQL, Excel, Power BI, or Tableau is beneficial
                    Understanding of software development lifecycle and Agile methodologies
                    Ability to gather and document functional requirements clearly
                    Strong organizational and stakeholder management skills
                    Attention to detail and critical thinking abilities
            """),

            // ── Marketing / Sales ─────────────────────────────────────────────────

            Map.entry("Marketing Manager", """
                    Overview :-
                    
                    A Marketing Manager is responsible for planning, executing, and managing marketing strategies to promote products, services, or brands.
                    They work to increase brand awareness, generate leads, and drive business growth through various marketing channels and campaigns.
                    
                    Responsibilities :-
                    
                    Develop and implement marketing strategies and campaigns
                    Manage digital marketing, social media, and advertising activities
                    Conduct market research and competitor analysis
                    Monitor campaign performance and marketing metrics
                    Collaborate with sales, product, and design teams
                    Create branding and promotional strategies
                    Manage content marketing, email campaigns, and SEO initiatives
                    Plan budgets and optimize marketing ROI
                    
                    Required Skills :-
                    
                    Strong understanding of digital marketing and branding strategies
                    Knowledge of SEO, SEM, social media marketing, and content marketing
                    Familiarity with tools such as Google Analytics, Meta Ads, or HubSpot
                    Excellent communication and leadership abilities
                    Strong analytical and problem-solving skills
                    Creativity and strategic thinking abilities
                    Project management and organizational skills
                    Ability to analyze marketing data and customer trends
            """),

            Map.entry("Sales Engineer", """
                    Overview :-
                    
                    A Sales Engineer is responsible for combining technical knowledge with sales skills to promote and sell technology products or solutions.
                    They work closely with customers to understand technical requirements and provide suitable product demonstrations and solutions.
                    
                    Responsibilities :-
                    
                    Understand customer technical requirements and business needs
                    Present and demonstrate products or technical solutions to clients
                    Support the sales team during client meetings and negotiations
                    Provide technical guidance and product recommendations
                    Prepare technical proposals, presentations, and documentation
                    Assist customers during product implementation and onboarding
                    Collaborate with engineering, product, and support teams
                    Build and maintain strong customer relationships
                    
                    Required Skills :-
                    
                    Strong technical knowledge of software, hardware, cloud, or IT solutions
                    Excellent communication and presentation abilities
                    Understanding of sales processes and customer engagement
                    Ability to explain technical concepts to non-technical audiences
                    Knowledge of CRM tools and sales tracking systems
                    Strong problem-solving and negotiation skills
                    Customer-focused mindset and relationship-building abilities
                    Ability to work with cross-functional teams effectively
            """),

            // ── Finance / HR / Content ────────────────────────────────────────────

            Map.entry("Finance Analyst", """
                    Overview :-
                    
                    A Finance Analyst is responsible for analyzing financial data, preparing reports, and providing insights to support business decisions and financial planning.
                    They help organizations improve profitability, manage budgets, and evaluate financial performance and risks.
                    
                    Responsibilities :-
                    
                    Analyze financial data, trends, and business performance
                    Prepare financial reports, forecasts, and budgeting plans
                    Monitor company expenses, revenue, and profitability
                    Conduct market and investment analysis
                    Support strategic planning and financial decision-making
                    Identify financial risks and recommend improvements
                    Collaborate with management and business teams
                    Maintain accurate financial records and documentation
                    
                    Required Skills :-
                    
                    Strong knowledge of finance, accounting, and financial analysis principles
                    Proficiency in Excel and financial modeling techniques
                    Familiarity with tools such as Power BI, Tableau, or ERP systems
                    Strong analytical and problem-solving abilities
                    Understanding of budgeting, forecasting, and investment analysis
                    Attention to detail and organizational skills
                    Good communication and presentation abilities
                    Ability to interpret and present complex financial information clearly
            """),

            Map.entry("HR Manager", """
                    Overview :-
                    
                    An HR Manager is responsible for managing recruitment, employee relations, performance management, and overall human resource operations within an organization.
                    They help build a productive work environment, ensure compliance with company policies, and support employee growth and organizational goals.
                    
                    Responsibilities :-
                    
                    Manage recruitment, hiring, and onboarding processes
                    Develop and implement HR policies and procedures
                    Handle employee relations and workplace conflict resolution
                    Monitor employee performance and conduct evaluations
                    Manage payroll, attendance, and employee benefits coordination
                    Ensure compliance with labor laws and company regulations
                    Organize training, development, and engagement programs
                    Support organizational culture and workforce planning
                    
                    Required Skills :-
                    
                    Strong communication and interpersonal abilities
                    Knowledge of HR management principles and labor laws
                    Experience with recruitment and talent acquisition processes
                    Familiarity with HR software and management systems
                    Problem-solving and conflict resolution skills
                    Leadership and organizational abilities
                    Ability to maintain confidentiality and professionalism
                    Strong decision-making and people management skills
            """),

            Map.entry("Technical Writer", """
                    Overview :-
                    
                    A Technical Writer is responsible for creating clear, accurate, and user-friendly documentation for software, products, systems, and technical processes.
                    They help users and developers understand complex technical information through manuals, guides, FAQs, and knowledge base content.
                    
                    Responsibilities :-
                    
                    Create and maintain technical documentation and user guides
                    Write API documentation, tutorials, and product manuals
                    Collaborate with developers, engineers, and product teams
                    Simplify complex technical concepts for different audiences
                    Update documentation based on product changes and releases
                    Organize and manage knowledge base content
                    Ensure documentation accuracy, consistency, and quality
                    Conduct research and gather technical information from subject matter experts
                    
                    Required Skills :-
                    
                    Excellent writing, editing, and communication abilities
                    Strong understanding of technical concepts and software systems
                    Familiarity with documentation tools such as Markdown, Confluence, or GitBook
                    Ability to explain complex topics in a simple and structured manner
                    Attention to detail and organizational skills
                    Basic understanding of APIs, software development, or cloud technologies
                    Research and analytical thinking abilities
                    Ability to work with cross-functional teams effectively
            """)
    );

    /**
     * Returns the default JD for a role, or an empty string if not found.
     * PromptBuilder uses this to build the effective JD before calling the LLM.
     */
    public static String getDefault(String roleLabel) {
        return JDS.getOrDefault(roleLabel, "");
    }
}